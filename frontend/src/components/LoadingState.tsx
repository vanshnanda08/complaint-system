/**
 * A loading state.
 *
 * A sentence, not a shimmer -- for small or fast regions.
 *
 * The original reasoning was the citizen constraint: a shimmering placeholder
 * on a cheap Android over a weak connection costs paint work exactly when the
 * device has none to spare, and tells the user nothing a word would not. That
 * still holds here, and this component still renders a word.
 *
 * What it does not cover is LAYOUT. A region that is one line of text and then
 * twenty-five rows shifts everything below it when the data lands, which on a
 * slow connection arrives just as somebody has started reading. Where a region
 * has a known shape and a real cost to reflowing it, use `Skeleton.tsx`
 * instead -- it holds the space, and its sweep stops dead under
 * prefers-reduced-motion. The split is by region, not by taste.
 *
 * Where progress is genuinely measurable -- a photo upload -- use a determinate
 * bar instead (see PhotoUploader). Users abandon indeterminate spinners, and
 * the one place this application can show a real percentage is the one place it
 * matters most.
 */
export function LoadingState({ label = "Loading" }: { label?: string }) {
  return (
    <p className="py-8 text-dense text-ink-muted" aria-live="polite">
      {label}…
    </p>
  );
}
