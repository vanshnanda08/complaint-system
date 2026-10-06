"use client";

import { PageShell } from "@/components/PageShell";
import { MetricTile } from "@/components/MetricTile";
import { ErrorState } from "@/components/ErrorState";
import { SkeletonTileGrid } from "@/components/Skeleton";
import { useDashboardSummary, useDepartmentAccountability } from "@/lib/queries";
import { absoluteDateTime } from "@/lib/format";
import type { ApiError } from "@/lib/api";
import type { DepartmentAccountability } from "@/lib/types";

/**
 * Accountability dashboard (blueprint §3.8).
 *
 * SCOPE FOR THIS PHASE. /dashboard/summary feeds the top row, and since phase 6
 * /dashboard/departments feeds the reopen and unverified-resolution figures
 * ([CHANGE 6], DD-006) -- in their own section with their own request, so
 * either can fail without blanking the other. Every other tile §3.8 specifies renders its
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
 *
 * The page intro and the "Not measured yet" section heading were removed at the
 * user's request: one dashboard, one grid, no prose. The unbuilt tiles keep
 * their dashed border and their "will show ..." sentence, which is now the only
 * thing distinguishing them -- so that styling is load-bearing rather than
 * decorative, and flattening it back to look like the live tiles would quietly
 * turn "not measured" into "measured, and the answer is nothing".
 */
export default function DashboardPage() {
  const summary = useDashboardSummary();

  return (
    <PageShell wide>
      <h1 className="text-display">Accountability dashboard</h1>

      {summary.isPending && <SkeletonTileGrid label="Loading the dashboard" />}

      {summary.isError && (
        <ErrorState
          action="Loading the dashboard"
          problem={(summary.error as ApiError)?.problem}
          onRetry={() => void summary.refetch()}
        />
      )}

      {summary.data && (
        <>
          <div className="mt-6 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 u-stagger">
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

          {/*
              The unbuilt metrics carry no heading and no explanation any more.
              They are still here, and they are still visibly different -- a
              dashed border, no fill, and a sentence saying what each will show.
              That is now the whole signal, and it has to carry the meaning the
              removed paragraph used to spell out: these are specified and not
              yet built, listed rather than hidden, so what the dashboard does
              not tell you stays as visible as what it does. The reasoning is
              unchanged; only the prose is gone.
          */}
          <div className="mt-3 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 u-stagger">
            <MetricTile
              label="Median resolution time"
              pending="Will show the median days to resolve, by ward and by department."
            />
            <MetricTile
              label="SLA compliance"
              pending="Will show the share of issues resolved before their deadline, with a 30-day trend."
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
        </>
      )}

      {/* Outside the summary's block: its own request, its own failure. */}
      <DepartmentRecord />

      {summary.data && (
        <>
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

const UNVERIFIED_EXPLANATION =
  "Anonymous reporters cannot vote on whether a fix worked, so those issues close on a 72-hour timeout. Publishing the rate is the mitigation, not a footnote — a dashboard that hides its own weakest metric is the thing this project argues against.";

/** A share as a whole percentage. Never called with a zero denominator. */
function pct(part: number, whole: number): string {
  return `${Math.round((part / whole) * 100)}%`;
}

/**
 * How claimed fixes fared with citizens, city-wide and per department.
 *
 * The two rates have different denominators, deliberately (see
 * IssueRepository.findDepartmentAccountability): unverified is out of issues
 * resolved, reopened is out of fixes ever claimed. A null rate is a department
 * with no denominator yet, and it says so in words -- "0%" would claim a
 * perfect record nobody has earned.
 */
function DepartmentRecord() {
  const departments = useDepartmentAccountability();

  if (departments.isPending) return <SkeletonTileGrid tiles={2} label="Loading the department figures" />;

  if (departments.isError) {
    return (
      <ErrorState
        action="Loading the department figures"
        problem={(departments.error as ApiError)?.problem}
        onRetry={() => void departments.refetch()}
      />
    );
  }

  const rows = departments.data;
  const total = (f: (d: DepartmentAccountability) => number) => rows.reduce((n, d) => n + f(d), 0);
  const resolved = total((d) => d.resolved);
  const unverified = total((d) => d.resolvedWithoutVerification);
  const claimed = total((d) => d.fixesClaimed);
  const reopened = total((d) => d.reopened);

  return (
    <section className="mt-8">
      <h2 className="text-heading">Did the fixes hold?</h2>

      <div className="mt-3 grid grid-cols-1 sm:grid-cols-2 gap-3 u-stagger">
        <MetricTile
          label="Reopen rate"
          {...(claimed === 0
            ? { pending: "Will show how often citizens rejected a fix, once a department has submitted one." }
            : {
                value: pct(reopened, claimed),
                trend: `${reopened.toLocaleString("en-IN")} of ${claimed.toLocaleString("en-IN")} claimed fixes were reopened`,
              })}
        />
        <MetricTile
          label="Resolved without citizen verification"
          explanation={UNVERIFIED_EXPLANATION}
          {...(resolved === 0
            ? { pending: "Will show the share of issues that closed with nobody confirming the fix, once one has closed." }
            : {
                value: pct(unverified, resolved),
                trend: `${unverified.toLocaleString("en-IN")} of ${resolved.toLocaleString("en-IN")} resolved issues`,
              })}
        />
      </div>

      <div className="mt-4 overflow-x-auto">
        <table className="w-full text-dense border-collapse">
          <caption className="text-left text-meta text-ink-muted pb-2">By department</caption>
          <thead>
            <tr className="border-b border-rule text-meta text-ink-muted">
              <th scope="col" className="text-left font-medium py-2 pr-4">Department</th>
              <th scope="col" className="text-right font-medium py-2 px-4">Reopened</th>
              <th scope="col" className="text-right font-medium py-2 pl-4">Resolved without verification</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((d) => (
              <tr key={d.departmentId} className="border-b border-rule">
                <th scope="row" className="text-left font-normal py-2 pr-4">{d.departmentName}</th>
                <td className="text-right py-2 px-4 tabular-nums">
                  {d.reopenRate === null ? (
                    <span className="text-ink-muted">No fixes claimed yet</span>
                  ) : (
                    <>
                      {pct(d.reopened, d.fixesClaimed)}{" "}
                      <span className="text-ink-muted">({d.reopened} of {d.fixesClaimed})</span>
                    </>
                  )}
                </td>
                <td className="text-right py-2 pl-4 tabular-nums">
                  {d.unverifiedRate === null ? (
                    <span className="text-ink-muted">Nothing resolved yet</span>
                  ) : (
                    <>
                      {pct(d.resolvedWithoutVerification, d.resolved)}{" "}
                      <span className="text-ink-muted">({d.resolvedWithoutVerification} of {d.resolved})</span>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
