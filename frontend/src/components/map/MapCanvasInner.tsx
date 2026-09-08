"use client";

/* ==========================================================================
   THE ONLY MODULE IN THIS CODEBASE THAT IMPORTS LEAFLET.
   --------------------------------------------------------------------------
   Do not import "leaflet" anywhere else. Leaflet touches `window` at module
   scope, so importing it from a module the Next.js server renders throws
   `window is not defined` during SSR -- and the failure surfaces as a broken
   page on a route with nothing obviously to do with maps, because the import
   chain reached it transitively.

   This file is never imported directly by a screen. It is loaded only by
   ../MapCanvas.tsx via dynamic(..., { ssr: false }), which is what guarantees
   it is evaluated in the browser and nowhere else.

   The second reason for the boundary is the bundle. Blueprint §8 budgets
   120 KB gzipped for the report composer's initial JS, and Leaflet alone is
   larger than that. Behind a dynamic import, the composer's happy path --
   photo, automatic location, category -- never downloads a map, and Leaflet
   arrives only if GPS accuracy is poor enough to force manual pin placement.
   ========================================================================== */

import { useEffect, useRef } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import type { MapCanvasProps, MapMarker } from "./types";

/**
 * Marker glyphs as divIcons.
 *
 * Not Leaflet's default image markers: those need asset files that break under
 * bundlers, and more importantly a status marker must carry shape as well as
 * colour (see src/lib/status.ts). A `divIcon` lets the marker be inline SVG in
 * the same glyph vocabulary the rest of the interface uses, so the map stays
 * legible in greyscale for the same reason the queue does.
 */
function markerIcon(m: MapMarker): L.DivIcon {
  const size = m.kind === "centroid" ? 22 : 16;
  const stroke = m.overdue ? 3 : 1.5;

  const svg =
    m.kind === "centroid"
      ? // A crosshair, visually distinct from the report dots it summarises.
        `<svg width="${size}" height="${size}" viewBox="0 0 22 22">
           <circle cx="11" cy="11" r="7" fill="none" stroke="${m.color}" stroke-width="2"/>
           <path d="M11 0v6M11 16v6M0 11h6M16 11h6" stroke="${m.color}" stroke-width="2"/>
         </svg>`
      : m.kind === "report"
        ? `<svg width="${size}" height="${size}" viewBox="0 0 16 16">
             <circle cx="8" cy="8" r="5" fill="${m.color}" stroke="#fff" stroke-width="2"/>
           </svg>`
        : // An issue on the browse map. Heavier stroke when overdue, so the
          // breached ones read first across a whole city.
          `<svg width="${size}" height="${size}" viewBox="0 0 16 16">
             <circle cx="8" cy="8" r="5.5" fill="${m.color}" stroke="${
               m.overdue ? "#14181A" : "#ffffff"
             }" stroke-width="${stroke}"/>
           </svg>`;

  return L.divIcon({
    html: svg,
    className: "civictrack-marker",
    iconSize: [size, size],
    iconAnchor: [size / 2, size / 2],
  });
}

const CIRCLE_STYLE = {
  // A report's own GPS accuracy. Translucent fill, because several overlapping
  // accuracy circles is the honest picture of a cluster's uncertainty.
  accuracy: { color: "#5A6469", weight: 1, fillOpacity: 0.08 },
  // The current effective merge radius.
  merge: { color: "#1D4ED8", weight: 2, fillOpacity: 0, dashArray: "6 4" },
  // DD-001's extent cap: maxExtentMultiplier x mergeRadiusM.
  extentCap: { color: "#B45309", weight: 2, fillOpacity: 0, dashArray: "2 5" },
} as const;

export default function MapCanvasInner({
  center,
  zoom = 15,
  markers = [],
  circles = [],
  height = 420,
  interactive = true,
  onViewportChange,
  onPinPlace,
  fitToMarkers = false,
}: MapCanvasProps) {
  const nodeRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<L.Map | null>(null);
  const layerRef = useRef<L.LayerGroup | null>(null);

  // Callbacks live in refs so a parent re-rendering with a new closure does not
  // tear down and rebuild the map, which would reset the viewport out from
  // under a user mid-pan. They are written in an effect rather than during
  // render: a render that mutates a ref is not a pure render, and under a
  // memoising compiler the write may not happen when you think it does.
  const viewportCb = useRef(onViewportChange);
  const pinCb = useRef(onPinPlace);

  useEffect(() => {
    viewportCb.current = onViewportChange;
    pinCb.current = onPinPlace;
  }, [onViewportChange, onPinPlace]);

  useEffect(() => {
    if (!nodeRef.current || mapRef.current) return;

    const map = L.map(nodeRef.current, {
      center: [center.lat, center.lng],
      zoom,
      zoomControl: interactive,
      dragging: interactive,
      scrollWheelZoom: interactive,
      doubleClickZoom: interactive,
      touchZoom: interactive,
      keyboard: interactive,
    });

    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
    }).addTo(map);

    layerRef.current = L.layerGroup().addTo(map);
    mapRef.current = map;

    let timer: number | undefined;
    if (viewportCb.current) {
      // 300ms debounce on moveend, per blueprint §3.4. Without it a single pan
      // fires a marker query per animation frame.
      map.on("moveend", () => {
        window.clearTimeout(timer);
        timer = window.setTimeout(() => {
          const b = map.getBounds();
          viewportCb.current?.({
            south: b.getSouth(),
            west: b.getWest(),
            north: b.getNorth(),
            east: b.getEast(),
            zoom: map.getZoom(),
          });
        }, 300);
      });
    }

    if (pinCb.current) {
      map.on("click", (e: L.LeafletMouseEvent) => {
        pinCb.current?.(e.latlng.lat, e.latlng.lng);
      });
    }

    return () => {
      window.clearTimeout(timer);
      map.remove();
      mapRef.current = null;
      layerRef.current = null;
    };
    // Mount-only on purpose. Later prop changes are applied by the effects
    // below rather than by rebuilding the map.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Recentre only when the caller moves the centre, never on every render.
  useEffect(() => {
    mapRef.current?.setView([center.lat, center.lng], zoom, { animate: false });
  }, [center.lat, center.lng, zoom]);

  // Redraw the overlay. Circles first so markers sit above them.
  useEffect(() => {
    const layer = layerRef.current;
    const map = mapRef.current;
    if (!layer || !map) return;
    layer.clearLayers();

    for (const c of circles) {
      L.circle([c.lat, c.lng], { radius: c.radiusM, ...CIRCLE_STYLE[c.style] }).addTo(layer);
    }

    for (const m of markers) {
      const marker = L.marker([m.lat, m.lng], {
        icon: markerIcon(m),
        keyboard: true,
        title: m.label,
        alt: m.label ?? "",
      });
      if (m.popupHtml) marker.bindPopup(m.popupHtml);
      if (m.onClick) marker.on("click", m.onClick);
      marker.addTo(layer);
    }

    if (fitToMarkers && markers.length > 0) {
      const bounds = L.latLngBounds(markers.map((m) => [m.lat, m.lng] as [number, number]));
      // Padded so the outermost accuracy circle is not clipped by the frame.
      map.fitBounds(bounds.pad(0.35), { animate: false, maxZoom: 18 });
    }
  }, [markers, circles, fitToMarkers]);

  return (
    <div
      ref={nodeRef}
      style={{ height, width: "100%", borderRadius: "var(--radius)" }}
      // The map is an interactive region; naming it stops a screen reader
      // announcing an unlabelled group of tile images.
      role="region"
      aria-label="Map"
    />
  );
}
