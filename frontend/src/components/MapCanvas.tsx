"use client";

import dynamic from "next/dynamic";
import type { MapCanvasProps } from "./map/types";

/**
 * The map, and the boundary that keeps Leaflet out of the server render.
 *
 * `ssr: false` is not a performance tweak. Leaflet touches `window` at module
 * scope, so any server-rendered module that reaches it transitively throws
 * during SSR. Loading it dynamically here means the import graph for every
 * screen stops at this file, and only the browser ever evaluates the module
 * that actually imports Leaflet (./map/MapCanvasInner).
 *
 * It is also what keeps the report composer inside its 120 KB budget: the
 * composer renders a map only when GPS accuracy is too poor to trust, so on
 * the happy path Leaflet is never downloaded at all.
 */
const MapCanvasInner = dynamic(() => import("./map/MapCanvasInner"), {
  ssr: false,
  loading: () => (
    <div
      className="flex items-center justify-center bg-surface-raised border border-rule"
      style={{ height: 420, borderRadius: "var(--radius)" }}
    >
      {/* A word, not a shimmer. Blueprint §3.4 also requires tiles never to
          block on the marker query -- that is handled by the screens, which
          render the map immediately and pass markers in when they arrive. */}
      <p className="text-dense text-ink-muted">Loading map…</p>
    </div>
  ),
});

export function MapCanvas(props: MapCanvasProps) {
  return <MapCanvasInner {...props} />;
}

export type { LatLng, MapCircle, MapMarker, Viewport, MapCanvasProps } from "./map/types";
