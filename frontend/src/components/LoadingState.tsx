/**
 * A loading state.
 *
 * A sentence, not a shimmer. Blueprint §1.7 rules out skeleton shimmer along
 * with every other entrance animation, and the reason is the citizen
 * constraint: a shimmering placeholder on a cheap Android over a weak
 * connection costs paint work at exactly the moment the device has none to
 * spare, and it tells the user nothing a word would not.
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
