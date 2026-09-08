import Link from "next/link";
import type { IssueStatus } from "@/lib/status";
import { StatusRule } from "./StatusRule";
import { DeadlineCountdown } from "./DeadlineCountdown";

/**
 * One ruled row of an issue list.
 *
 * A ruled row, not a card. Blueprint §1.5 makes the hairline rule the dominant
 * structural device and confines cards to the dashboard metric tiles, where an
 * item is genuinely detachable. Rows keep mobile scroll density high and stop
 * the interface reading as a card kit.
 *
 * Responsive collapse is in a fixed order (blueprint §3.5): reporter count and
 * ward drop to the second line on mobile; the ticket reference never hides,
 * because it is the thing a person is holding on a scrap of paper.
 *
 * The row renders `DeadlineCountdown` rather than taking a formatted deadline
 * string. An earlier draft took a string, and it made the caller responsible
 * for knowing that a PENDING_VERIFICATION clock is paused -- which is exactly
 * the knowledge that must live in one component and not in nine callers.
 */

export interface IssueRowProps {
  id: string;
  publicRef: string;
  categoryName: string;
  wardName: string;
  status: IssueStatus;
  distinctReporterCount: number;
  overdue?: boolean;
  ageLabel: string;
  effectiveDeadline: string;
  pausedSeconds?: number;
  resolvedAt?: string | null;
  landmark?: string | null;
  audience?: "public" | "staff";
  href?: string;
}

export function IssueRow({
  id,
  publicRef,
  categoryName,
  wardName,
  status,
  distinctReporterCount,
  overdue = false,
  ageLabel,
  effectiveDeadline,
  pausedSeconds = 0,
  resolvedAt = null,
  landmark,
  audience = "public",
  href,
}: IssueRowProps) {
  return (
    <Link
      href={href ?? `/issues/${id}`}
      className="block border-b border-rule py-3 no-underline text-ink"
      style={{ minHeight: "var(--hit-min)" }}
    >
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
        <StatusRule status={status} overdue={overdue} audience={audience} />

        {/* Mono, because a reference is read aloud over a phone, compared digit
            by digit, and pasted into WhatsApp. Fixed-width serves all three. */}
        <span className="text-ref font-mono">{publicRef}</span>

        <span className="text-dense">{categoryName}</span>

        <span className="ml-auto">
          <DeadlineCountdown
            status={status}
            effectiveDeadline={effectiveDeadline}
            pausedSeconds={pausedSeconds}
            resolvedAt={resolvedAt}
          />
        </span>
      </div>

      <div className="mt-1 flex flex-wrap items-center gap-x-4 text-meta text-ink-muted">
        <span>{wardName}</span>
        <span>
          {distinctReporterCount === 1 ? "1 reporter" : `${distinctReporterCount} reporters`}
        </span>
        <span>{ageLabel}</span>
        {landmark && <span>{landmark}</span>}
      </div>
    </Link>
  );
}
