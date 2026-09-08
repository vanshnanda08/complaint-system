/**
 * A dashboard metric tile.
 *
 * The one place in the system that uses a card, because each tile is
 * independently screenshottable and that is what a journalist or a
 * councillor's office actually does with this page (blueprint 3.8). Everywhere
 * else, a hairline rule.
 *
 * `explanation` exists for one specific tile -- resolved-without-verification
 * -- which is the only metric a visitor will not immediately understand and
 * the one the project is least flattered by. A dashboard that hides its own
 * weakest metric is the thing this project exists to argue against.
 *
 * `pending` renders the blueprint's empty state: what the tile will show and
 * what it needs before it can. Not a zero and not a dash, because "0" is a
 * claim about the world and "we cannot tell you yet" is not the same claim.
 */

export interface MetricTileProps {
  label: string;
  value?: string;
  unit?: string;
  trend?: string;
  explanation?: string;
  pending?: string;
}

export function MetricTile({ label, value, unit, trend, explanation, pending }: MetricTileProps) {
  return (
    <section
      className="bg-surface-raised border border-rule p-4"
      style={{ borderRadius: "var(--radius)" }}
    >
      <h3 className="text-meta text-ink-muted">{label}</h3>

      {pending ? (
        <p className="mt-2 text-dense text-ink-muted">{pending}</p>
      ) : (
        <p className="mt-1 flex items-baseline gap-1.5">
          <span className="text-display">{value}</span>
          {unit && <span className="text-meta text-ink-muted">{unit}</span>}
        </p>
      )}

      {trend && !pending && <p className="mt-1 text-meta text-ink-muted">{trend}</p>}

      {explanation && (
        <p className="mt-2 text-meta text-ink-muted" style={{ maxWidth: "42ch" }}>
          {explanation}
        </p>
      )}
    </section>
  );
}
