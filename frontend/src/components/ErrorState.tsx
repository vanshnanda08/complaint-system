"use client";

import type { ProblemDetail } from "@/lib/types";
import { Button } from "./Button";

/**
 * An error: what happened, and what to do about it.
 *
 * Never a generic banner (blueprint §3.2, §9). When the server sent an RFC 7807
 * problem, its `detail` is rendered VERBATIM and is not paraphrased, summarised
 * or prefixed with an apology. The state machine is the authority on why a
 * transition was refused, and a client that rewords "Staff cannot resolve an
 * issue; submit it for citizen verification" into "Something went wrong" has
 * thrown away the only useful sentence in the response.
 */

export interface ErrorStateProps {
  /** What the client was trying to do, for when the server said nothing useful. */
  action: string;
  problem?: ProblemDetail | null;
  onRetry?: () => void;
  retryLabel?: string;
}

export function ErrorState({ action, problem, onRetry, retryLabel = "Try again" }: ErrorStateProps) {
  const detail = problem?.detail?.trim();

  return (
    <div className="py-6" style={{ maxWidth: "var(--measure-prose)" }} role="alert">
      <p className="text-body" style={{ color: "var(--st-breached)" }}>
        {detail ?? `${action} failed because the server could not be reached.`}
      </p>
      {!detail && (
        <p className="mt-2 text-dense text-ink-muted">
          Your connection may have dropped. Nothing was lost.
        </p>
      )}
      {onRetry && (
        <div className="mt-4">
          <Button variant="secondary" onClick={onRetry}>
            {retryLabel}
          </Button>
        </div>
      )}
    </div>
  );
}
