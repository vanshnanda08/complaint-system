"use client";

import { clockRunning, type IssueStatus } from "@/lib/status";
import { absoluteDateTime, humaniseMs } from "@/lib/format";
import { useNow } from "@/lib/useNow";

/**
 * The SLA clock, including when it is not running.
 *
 * THE PAUSE IS THE POINT. During PENDING_VERIFICATION the deadline genuinely
 * stops: the department is waiting on citizens and is not charged for the
 * delay (`IssueStatus.clockRunning()` on the server). Rendering a ticking
 * countdown in that state would show a department losing time it is not
 * losing -- a false accusation in a system whose entire purpose is measuring
 * departments fairly. So a paused clock renders as paused, in words, and does
 * not tick.
 *
 * `effectiveDeadline` is `dueAt + pausedSeconds`, computed once on the server
 * and shipped on every issue DTO. It is used verbatim. Re-deriving it here
 * would put a second definition of the SLA clock in TypeScript, and the two
 * would eventually disagree.
 *
 * Split into three components so that a stopped clock subscribes to no timer
 * at all -- hooks cannot be called conditionally, so the condition is
 * expressed as which component renders.
 */

export interface DeadlineCountdownProps {
  status: IssueStatus;
  effectiveDeadline: string;
  pausedSeconds?: number;
  resolvedAt?: string | null;
}

export function DeadlineCountdown({
  status,
  effectiveDeadline,
  pausedSeconds = 0,
  resolvedAt = null,
}: DeadlineCountdownProps) {
  if (status === "RESOLVED" || status === "CLOSED") {
    return (
      <span className="text-meta text-ink-muted">
        {resolvedAt ? `Resolved ${absoluteDateTime(resolvedAt)}` : "Clock stopped"}
      </span>
    );
  }

  if (status === "REJECTED") {
    return <span className="text-meta text-ink-muted">Clock stopped</span>;
  }

  if (!clockRunning(status)) {
    return <PausedClock effectiveDeadline={effectiveDeadline} pausedSeconds={pausedSeconds} />;
  }

  return <RunningClock effectiveDeadline={effectiveDeadline} />;
}

/**
 * PENDING_VERIFICATION. Says that the clock is paused, and how much was left
 * when it stopped -- because that remainder is what the department gets back
 * when the clock restarts, and it is the number that makes the pause legible
 * as a fairness mechanism rather than as a stalled row.
 */
function PausedClock({
  effectiveDeadline,
  pausedSeconds,
}: {
  effectiveDeadline: string;
  pausedSeconds: number;
}) {
  const now = useNow();
  const remaining = now === 0 ? null : Date.parse(effectiveDeadline) - now;

  return (
    <span className="text-meta" style={{ color: "var(--st-pending)" }}>
      Clock paused
      {remaining !== null && remaining > 0 ? ` — ${humaniseMs(remaining)} remained` : ""}
      {pausedSeconds > 0 ? ` · paused ${humaniseMs(pausedSeconds * 1000)} so far` : ""}
    </span>
  );
}

function RunningClock({ effectiveDeadline }: { effectiveDeadline: string }) {
  const now = useNow();

  // Before the client clock is known, the absolute deadline: true, stable, and
  // the same on server and client, so hydration cannot mismatch.
  if (now === 0) {
    return (
      <span className="text-meta text-ink-muted">Due {absoluteDateTime(effectiveDeadline)}</span>
    );
  }

  const remaining = Date.parse(effectiveDeadline) - now;

  if (remaining < 0) {
    return (
      <span className="text-meta" style={{ color: "var(--st-breached)" }}>
        Overdue by {humaniseMs(remaining)}
      </span>
    );
  }

  // Within four hours of breach the row carries the pending treatment
  // (blueprint §3.14), so a crew can see what is about to go red rather than
  // only what already has.
  const nearBreach = remaining < 4 * 60 * 60 * 1000;
  return (
    <span
      className="text-meta"
      style={{ color: nearBreach ? "var(--st-pending)" : "var(--ink-muted)" }}
    >
      {humaniseMs(remaining)} left
    </span>
  );
}
