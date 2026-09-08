/**
 * A determinate upload bar.
 *
 * Determinate on purpose: blueprint §3.2 states that users abandon
 * indeterminate spinners, and the photo upload is both the slow part of the
 * report flow and the only step where a real percentage is available.
 *
 * The bar is ink, not a status colour. Progress is not a status.
 */
export function UploadProgress({ fraction }: { fraction: number }) {
  const pct = Math.round(Math.min(1, Math.max(0, fraction)) * 100);
  return (
    <div className="flex items-center gap-3">
      <div
        className="flex-1 bg-surface border border-rule overflow-hidden"
        style={{ height: 8, borderRadius: "var(--radius-sm)" }}
        role="progressbar"
        aria-valuenow={pct}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label="Uploading photo"
      >
        <div style={{ width: `${pct}%`, height: "100%", background: "var(--ink)" }} />
      </div>
      <span className="text-meta text-ink-muted tabular-nums">{pct}%</span>
    </div>
  );
}
