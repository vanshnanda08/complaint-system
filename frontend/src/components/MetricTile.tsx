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
 * what it needs before it can. NOT a zero and NOT a dash, because "0" is a
 * claim about the world, "-" reads as one, and "we cannot tell you yet" is a
 * different claim from either. This distinction is the reason the tile takes a
 * sentence here instead of a value.
 *
 * `emphasis` fills the tile. The fill is --tile-emph-bg, which is the breach
 * red, because the only tile that gets it is a breach count -- an emphasised
 * tile still obeys the rule that colour encodes status. It is deliberately not
 * the brand colour: brand marks identity, and a number is not identity.
 */

export interface MetricTileProps {
  label: string;
  value?: string;
  unit?: string;
  trend?: string;
  explanation?: string;
  pending?: string;
  /** Fill the tile. At most one tile on a page should carry it. */
  emphasis?: boolean;
}

export function MetricTile({
  label,
  value,
  unit,
  trend,
  explanation,
  pending,
  emphasis,
}: MetricTileProps) {
  const filled = emphasis && !pending;

  return (
    <section
      className={`p-5 relative flex flex-col h-full border u-lift ${
        filled
          ? "bg-tile-emph-bg border-tile-emph-bg text-tile-emph-ink"
          : pending
            ? // Not-yet-measured tiles are dashed rather than faded. An earlier
              // version dimmed them to 40% opacity, which measured 1.66:1 --
              // illegible, and the opposite of the intent, since the whole
              // point of listing them is that what the dashboard does NOT
              // measure should be as visible as what it does.
              "bg-surface border-rule border-dashed text-ink"
            : "bg-surface-raised border-rule text-ink"
      }`}
      style={{ borderRadius: "var(--radius)" }}
    >
      <h3
        className={`text-heading font-semibold leading-tight ${
          filled ? "text-tile-emph-ink" : "text-ink"
        }`}
      >
        {label}
      </h3>

      {pending ? (
        <div className="flex-1 flex items-start mt-2">
          <p className="text-dense text-ink-muted">{pending}</p>
        </div>
      ) : (
        <div className="mt-auto pt-4">
          <p className="flex items-baseline gap-1.5">
            <span className="text-[2.5rem] leading-[2.5rem] font-semibold tracking-tight">
              {value}
            </span>
            {unit && (
              <span className={`text-meta ${filled ? "text-tile-emph-ink-muted" : "text-ink-muted"}`}>
                {unit}
              </span>
            )}
          </p>
          {trend && (
            <p
              className={`mt-3 text-meta ${
                filled ? "text-tile-emph-ink-muted" : "text-ink-muted"
              }`}
            >
              {trend}
            </p>
          )}
        </div>
      )}

      {explanation && (
        <div
          className={`mt-4 pt-3 border-t ${filled ? "border-tile-emph-ink-muted" : "border-rule"}`}
        >
          <p
            className={`text-meta leading-snug ${
              filled ? "text-tile-emph-ink-muted" : "text-ink-muted"
            }`}
            style={{ maxWidth: "42ch" }}
          >
            {explanation}
          </p>
        </div>
      )}
    </section>
  );
}
