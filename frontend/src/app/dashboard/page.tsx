"use client";

import { PageShell } from "@/components/PageShell";
import { MetricTile } from "@/components/MetricTile";
import { ErrorState } from "@/components/ErrorState";
import { LoadingState } from "@/components/LoadingState";
import { useDashboardSummary } from "@/lib/queries";
import { absoluteDateTime } from "@/lib/format";
import type { ApiError } from "@/lib/api";

/**
 * Accountability dashboard (blueprint §3.8).
 *
 * SCOPE FOR THIS PHASE. Only /dashboard/summary exists, so only the tiles it
 * feeds carry live numbers. Every other tile §3.8 specifies renders its
 * blueprint-specified empty state -- "what it will show and how many resolved
 * issues it needs" -- rather than a zero or a dash.
 *
 * That is not a stub standing in for the real thing. It is §3.8's own empty
 * state, and it is more honest than four working tiles beside eight blanks: a
 * zero is a claim about the world, and "we are not measuring this yet" is a
 * different claim. The aggregate queries and the SSE live updates are phase 7.
 *
 * Tiles fail independently. `overdueCount` is nullable precisely so one failing
 * aggregate cannot blank the page -- so a null renders the sentence saying so,
 * never a dash. A dash in a number's place is read as a number.
 */
export default function DashboardPage() {
  const summary = useDashboardSummary();

  return (
    <PageShell wide>
      <h1 className="text-display">Accountability dashboard</h1>
      <p className="mt-2 text-body" style={{ maxWidth: "var(--measure-prose)" }}>
        Everything here is public and needs no account. The numbers are the same
        ones the escalation system acts on, not a separate report.
      </p>

      {summary.isPending && <LoadingState label="Loading the dashboard" />}

      {summary.isError && (
        <ErrorState
          action="Loading the dashboard"
          problem={(summary.error as ApiError)?.problem}
          onRetry={() => void summary.refetch()}
        />
      )}

      {summary.data && (
        <>
          <div className="mt-6 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
            <MetricTile
              label="Currently overdue"
              emphasis
              {...(summary.data.overdueCount === null
                ? {
                    pending:
                      "This figure could not be computed just now. It will return on the next refresh.",
                  }
                : {
                    value: summary.data.overdueCount.toLocaleString("en-IN"),
                    trend: "Breached and still on the clock",
                  })}
            />

            <MetricTile
              label="Total resolved to date"
              value={summary.data.totalResolved.toLocaleString("en-IN")}
            />

            <MetricTile
              label="Ward holding the most overdue work"
              {...(summary.data.topOverdueWard
                ? {
                    value: String(summary.data.topOverdueWard.count),
                    unit: `in ${summary.data.topOverdueWard.name}`,
                  }
                : { pending: "Nothing is overdue in any ward." })}
            />

            <MetricTile
              label="Department holding the most overdue work"
              {...(summary.data.topOverdueDepartment
                ? {
                    value: String(summary.data.topOverdueDepartment.count),
                    unit: `in ${summary.data.topOverdueDepartment.name}`,
                  }
                : { pending: "No department is holding overdue work." })}
            />
          </div>

          <h2 className="mt-10 text-heading">Not measured yet</h2>
          <p className="mt-1 text-dense text-ink-muted" style={{ maxWidth: "var(--measure-prose)" }}>
            These are specified and not yet built. They are listed rather than
            hidden, so what the dashboard does not currently tell you is as
            visible as what it does.
          </p>

          <div className="mt-4 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
            <MetricTile
              label="Median resolution time"
              pending="Will show the median days to resolve, by ward and by department."
            />
            <MetricTile
              label="SLA compliance"
              pending="Will show the share of issues resolved before their deadline, with a 30-day trend."
            />
            <MetricTile
              label="Reopen rate per department"
              pending="Will show how often a fix did not hold."
            />
            <MetricTile
              label="Resolved without citizen verification"
              pending="Will show the share of issues that closed with nobody confirming the fix."
              explanation="Anonymous reporters cannot vote on whether a fix worked, so those issues close on a 72-hour timeout. Publishing the rate is the mitigation, not a footnote — a dashboard that hides its own weakest metric is the thing this project argues against."
            />
            <MetricTile
              label="Open backlog age"
              pending="Will show how long open issues have been waiting, in buckets."
            />
            <MetricTile
              label="Reported against resolved"
              pending="Will show a 90-day daily series of both."
            />
          </div>

          <p className="mt-8 text-meta text-ink-muted">
            Figures generated {absoluteDateTime(summary.data.generatedAt)}. Live
            updates arrive with the event stream in a later phase; this page is
            refetched on load.
          </p>
        </>
      )}
    </PageShell>
  );
}
