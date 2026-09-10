"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { PageShell } from "@/components/PageShell";
import { EmptyState } from "@/components/EmptyState";
import { ErrorState } from "@/components/ErrorState";
import { LoadingState } from "@/components/LoadingState";
import { IssueRow } from "@/components/IssueRow";
import { useAuth } from "@/lib/auth";
import { ApiError, request } from "@/lib/api";
import { isOverdue } from "@/lib/status";
import { ageLabel } from "@/lib/format";
import type { MyReportsPage } from "@/lib/types";
import { useSignIn } from "@/lib/signInDialog";

/**
 * My reports (blueprint §3.11).
 *
 * The row grain is the REPORT, not the issue, so "whether their report merged
 * into an existing issue" is answerable per submission. A citizen who reported
 * the same pothole twice, weeks apart, has two rows and one ticket.
 *
 * Anonymous reports are not here and cannot be. Reports carry a device id, but
 * a device is not an identity the server will authenticate -- treating one as a
 * credential would let anybody who learned a device id read that phone's
 * history. This is the concrete cost of anonymous reporting, and the composer
 * states it up front rather than surprising somebody here.
 */
export default function MyReportsPageRoute() {
  const { openSignIn } = useSignIn();
  const { session, initialising, authed } = useAuth();

  const reports = useQuery({
    queryKey: ["me", "reports", session?.userId],
    queryFn: () => authed((token) => request<MyReportsPage>("/me/reports?limit=50", { token })),
    enabled: Boolean(session),
    staleTime: 30_000,
  });

  if (initialising) {
    return (
      <PageShell wide>
        <LoadingState label="Checking your session" />
      </PageShell>
    );
  }

  if (!session) {
    return (
      <PageShell>
        <h1 className="text-display">Your reports</h1>
        <EmptyState
          message="Sign in to see the problems you have reported."
          actionLabel="Sign in"
          onAction={openSignIn}
        />
      </PageShell>
    );
  }

  return (
    <PageShell wide>
      <h1 className="text-display">Your reports</h1>

      {reports.isPending && <LoadingState label="Loading your reports" />}

      {reports.isError && (
        <ErrorState
          action="Loading your reports"
          problem={(reports.error as ApiError)?.problem}
          onRetry={() => void reports.refetch()}
        />
      )}

      {reports.data && reports.data.items.length === 0 && (
        <EmptyState
          message="You haven't reported anything yet."
          actionLabel="Report a problem"
          actionHref="/report"
        />
      )}

      {reports.data && reports.data.items.length > 0 && (
        <>
          <p className="mt-4 text-meta text-ink-muted">
            {reports.data.total} report{reports.data.total === 1 ? "" : "s"}
          </p>

          <div className="mt-2 border-t border-rule">
            {reports.data.items.map((r) => (
              <div key={r.reportId}>
                <IssueRow
                  id={r.issue.id}
                  publicRef={r.issue.publicRef}
                  categoryName={r.issue.categoryName}
                  wardName={r.issue.wardName}
                  status={r.issue.status}
                  distinctReporterCount={r.issue.distinctReporterCount}
                  overdue={isOverdue(r.issue.status, r.issue.effectiveDeadline)}
                  ageLabel={ageLabel(r.reportedAt)}
                  effectiveDeadline={r.issue.effectiveDeadline}
                  pausedSeconds={r.issue.pausedSeconds}
                  resolvedAt={r.issue.resolvedAt}
                  landmark={r.landmark}
                />
                {r.merged && (
                  // Plain language. The citizen never sees "merged into issue
                  // 432" (blueprint §9) -- they see that other people reported
                  // the same thing, which is the fact that matters to them.
                  <p className="text-meta text-ink-muted -mt-2 mb-3">
                    Others had already reported this, so your report joined
                    theirs — {r.issue.distinctReporterCount} people in total.
                  </p>
                )}
              </div>
            ))}
          </div>
        </>
      )}

      <p className="mt-6 text-meta text-ink-muted" style={{ maxWidth: "var(--measure-prose)" }}>
        Reports you sent without signing in are not listed here. They were still
        recorded and still count — they just cannot be tied to your account.{" "}
        <Link href="/issues" className="underline text-ink">
          Search the issue index
        </Link>{" "}
        if you have the ticket number.
      </p>
    </PageShell>
  );
}
