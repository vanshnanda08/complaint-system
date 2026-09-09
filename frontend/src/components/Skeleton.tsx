/**
 * Placeholders shaped like the thing that is loading.
 *
 * WHY THIS EXISTS, given that LoadingState deliberately does not shimmer.
 *
 * `LoadingState` renders a sentence, and its reasoning still holds for small
 * or fast regions: a word costs nothing to paint and says more than a grey
 * box. What a sentence cannot do is hold the LAYOUT. A list that is one line
 * of text and then twenty-five rows shifts everything below it the moment the
 * data lands, and on a slow connection that shift arrives exactly as somebody
 * has started reading or reaching for a link.
 *
 * So the rule is by region, not by taste: a skeleton where the region has a
 * known shape and a real cost to reflowing it, a sentence everywhere else.
 *
 * The shimmer is a single directional sweep rather than a pulse, and it stops
 * entirely under `prefers-reduced-motion` -- flattening to a static fill, not
 * freezing mid-sweep, which is what zeroing the duration alone would do.
 *
 * Everything here is `aria-hidden`. A screen reader gets the one polite live
 * region announcing "Loading issues", not twenty-five announcements of nothing.
 */

export function SkeletonLine({
  width = "100%",
  height = 12,
}: {
  width?: number | string;
  height?: number;
}) {
  return <span className="u-skeleton block" style={{ width, height }} aria-hidden="true" />;
}

/** Matches IssueRow: a status line, then a metadata line. */
export function SkeletonIssueRow() {
  return (
    <div
      className="border-b border-rule py-3 px-2 -mx-2 flex flex-col gap-2"
      style={{ minHeight: "var(--hit-min)" }}
      aria-hidden="true"
    >
      <div className="flex items-center gap-4">
        <SkeletonLine width={96} height={14} />
        <SkeletonLine width={120} height={14} />
        <SkeletonLine width={90} height={14} />
        <span className="ml-auto">
          <SkeletonLine width={80} height={14} />
        </span>
      </div>
      <div className="flex items-center gap-4">
        <SkeletonLine width={140} height={10} />
        <SkeletonLine width={70} height={10} />
        <SkeletonLine width={90} height={10} />
      </div>
    </div>
  );
}

/**
 * A list of row skeletons.
 *
 * The count matters: too few and the page still jumps when the real rows
 * arrive, which is the whole problem this is here to solve.
 */
export function SkeletonIssueList({ rows = 6, label = "Loading issues" }: { rows?: number; label?: string }) {
  return (
    <div>
      <span className="sr-only" aria-live="polite">
        {label}…
      </span>
      {Array.from({ length: rows }, (_, i) => (
        <SkeletonIssueRow key={i} />
      ))}
    </div>
  );
}

/** Matches MetricTile. */
export function SkeletonTile() {
  return (
    <section
      className="p-5 border border-rule bg-surface-raised flex flex-col h-full gap-4"
      style={{ borderRadius: "var(--radius)" }}
      aria-hidden="true"
    >
      <SkeletonLine width="70%" height={16} />
      <div className="mt-auto pt-4">
        <SkeletonLine width={90} height={34} />
      </div>
    </section>
  );
}

export function SkeletonTileGrid({ tiles = 4, label = "Loading the dashboard" }: { tiles?: number; label?: string }) {
  return (
    <>
      <span className="sr-only" aria-live="polite">
        {label}…
      </span>
      <div className="mt-6 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
        {Array.from({ length: tiles }, (_, i) => (
          <SkeletonTile key={i} />
        ))}
      </div>
    </>
  );
}
