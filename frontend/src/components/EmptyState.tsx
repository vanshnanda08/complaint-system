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
  actionHref?: string;
}

export function EmptyState({ message, actionLabel, actionHref }: EmptyStateProps) {
  return (
    <div className="py-8" style={{ maxWidth: "var(--measure-prose)" }}>
      <p className="text-body">{message}</p>
      {actionLabel && actionHref && (
        <p className="mt-3">
          <Link href={actionHref} className="text-body underline text-ink">
            {actionLabel}
          </Link>
        </p>
      )}
    </div>
  );
}
