/**
 * A cluster at a glance: member report positions around the centroid, with
 * the extent cap drawn as the frame (blueprint 3.17, "a thumbnail cluster
 * preview").
 *
 * Positions are projected locally -- metres east and north of the centroid on
 * a flat approximation. That is a drawing, not a measurement: every distance
 * that decides anything is still computed by PostGIS (standing rule 2). Over a
 * cluster a hundred metres across the flat error is far below a pixel.
 */
const M_PER_DEG_LAT = 111_320;

export function ClusterThumbnail({
  lat,
  lng,
  points,
  capM,
  size = 96,
}: {
  lat: number;
  lng: number;
  points: { lat: number; lng: number }[];
  capM: number;
  size?: number;
}) {
  const mPerDegLng = M_PER_DEG_LAT * Math.cos((lat * Math.PI) / 180);
  const half = size / 2;
  // Scale so the cap circle fills the frame, or the furthest point does if a
  // point lies beyond the cap (which is exactly what an extent-cap flag shows).
  const offsets = points.map((p) => ({ x: (p.lng - lng) * mPerDegLng, y: (p.lat - lat) * M_PER_DEG_LAT }));
  const reach = Math.max(capM, ...offsets.map((o) => Math.hypot(o.x, o.y)), 1);
  const k = (half - 6) / reach;

  return (
    <svg
      width={size}
      height={size}
      role="img"
      aria-label={`${points.length} report${points.length === 1 ? "" : "s"} around the centre, against a ${Math.round(capM)} metre cap`}
      className="shrink-0 bg-surface border border-rule"
      style={{ borderRadius: "var(--radius)" }}
    >
      <circle cx={half} cy={half} r={capM * k} fill="none" stroke="var(--rule-strong)" strokeWidth={1} />
      <line x1={half - 4} x2={half + 4} y1={half} y2={half} stroke="var(--ink-muted)" strokeWidth={1} />
      <line x1={half} x2={half} y1={half - 4} y2={half + 4} stroke="var(--ink-muted)" strokeWidth={1} />
      {offsets.map((o, i) => (
        <circle key={i} cx={half + o.x * k} cy={half - o.y * k} r={3.5} fill="var(--ink)" stroke="var(--surface)" strokeWidth={1.5} />
      ))}
    </svg>
  );
}
