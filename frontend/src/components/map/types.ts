/**
 * The MapCanvas contract.
 *
 * Kept in its own module with no Leaflet import, so screens can type against it
 * without pulling Leaflet into their bundle. That is the reason these types do
 * not live next to the implementation: importing a type from MapCanvasInner
 * would be an import of MapCanvasInner.
 */

export interface LatLng {
  lat: number;
  lng: number;
}

export interface Viewport {
  south: number;
  west: number;
  north: number;
  east: number;
  zoom: number;
}

export interface MapMarker {
  id: string;
  lat: number;
  lng: number;
  /** `centroid` is a crosshair; `report` a dot; `issue` a browse-map pin. */
  kind: "issue" | "report" | "centroid";
  /** A resolved status colour. The map does not own the status vocabulary. */
  color: string;
  overdue?: boolean;
  label?: string;
  /**
   * How many separate people reported this issue. The marker grows with it, so
   * a nine-reporter drain reads before a one-reporter pothole across a whole
   * city — which is the judgement a crew has to make at a glance and the one
   * thing a uniform dot cannot express.
   */
  reporters?: number;
  /** Draws the marker as the selected one: larger, with a ring. */
  selected?: boolean;
  popupHtml?: string;
  onClick?: () => void;
}

export interface MapCircle {
  lat: number;
  lng: number;
  radiusM: number;
  /** accuracy = a report's own GPS circle; merge = effective radius; extentCap = DD-001. */
  style: "accuracy" | "merge" | "extentCap";
}

export interface MapCanvasProps {
  center: LatLng;
  zoom?: number;
  markers?: MapMarker[];
  circles?: MapCircle[];
  height?: number;
  interactive?: boolean;
  onViewportChange?: (v: Viewport) => void;
  onPinPlace?: (lat: number, lng: number) => void;
  fitToMarkers?: boolean;
}
