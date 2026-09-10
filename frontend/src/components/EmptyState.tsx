import Link from "next/link";

/**
 * An empty state: one sentence, one action.
 *
 * No illustration, ever (blueprint §1.7 and §4). And the sentence is written as
 * a statement of fact rather than an apology -- "Nothing needs review" is a
 * good outcome and reads as one (blueprint §9). If a caller finds themselves
 * writing "Sorry" or "Oops" into `message`, the copy is wrong, not this
 * component.
 */

export interface EmptyStateProps {
  message: string;
  actionLabel?: string;
  /** Navigate somewhere. Mutually exclusive with `onAction`. */
  actionHref?: string;
  /**
   * Do something in place instead of navigating -- opening the sign-in dialog,
   * mostly. Preferred over `actionHref` for anything that does not genuinely
   * change what page you are on: an empty state that says "sign in to see your
   * reports" and then takes you off the page has answered a question with a
   * detour.
   */
  onAction?: () => void;
}

export function EmptyState({ message, actionLabel, actionHref, onAction }: EmptyStateProps) {
  return (
    <div className="py-8" style={{ maxWidth: "var(--measure-prose)" }}>
      <p className="text-body">{message}</p>
      {actionLabel && onAction && (
        <p className="mt-3">
          <button
            type="button"
            onClick={onAction}
            className="text-body underline text-ink bg-transparent border-0 p-0 cursor-pointer"
          >
            {actionLabel}
          </button>
        </p>
      )}
      {actionLabel && actionHref && !onAction && (
        <p className="mt-3">
          <Link href={actionHref} className="text-body underline text-ink">
            {actionLabel}
          </Link>
        </p>
      )}
    </div>
  );
}
