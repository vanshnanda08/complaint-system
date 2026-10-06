"use client";

import Link from "next/link";
import { PageShell } from "@/components/PageShell";
import { MetricTile } from "@/components/MetricTile";
import { ErrorState } from "@/components/ErrorState";
import { SkeletonTileGrid } from "@/components/Skeleton";
import { StatusRule } from "@/components/StatusRule";
import { TicketRef } from "@/components/TicketRef";
import { TrendChart } from "@/components/charts/TrendChart";
import { ColumnChart } from "@/components/charts/ColumnChart";
import { shortDay } from "@/components/charts/chartKit";
import {
  useBreaching,
  useDashboardMetrics,
  useDashboardStream,
  useDashboardSummary,
  useDepartmentAccountability,
  type StreamState,
} from "@/lib/queries";
import { absoluteDateTime, humaniseMs, reporters } from "@/lib/format";
import type { ApiError } from "@/lib/api";
import type { DashboardMetrics, DepartmentAccountability, Median } from "@/lib/types";

/**
 * Accountability dashboard (blueprint §3.8), complete as of phase 7.
 *
 * Three requests, each failing on its own: the summary row, the aggregates
 * (/dashboard/metrics, every section of which is itself nullable), and the
 * per-department verification record. A failing median cannot blank the
 * overdue count.
 *
 * LIVE. The overdue tile and the breaching list subscribe to the event stream
 * and refetch when the server says they changed; everything else refetches
 * every 60 seconds (§3.8). The page says "live" only while the stream is
 * connected -- a dashboard that claims liveness through a dropped connection
 * is showing a stale number with a fresh label.
 *
 * EMPTY. Below the minimum sample a figure is withheld and its tile says how
 * many more resolved issues it needs (§3.8: "Not zeros, not dashes"). A median
 * of three issues is an anecdote with a decimal point.
 *
 * COLOUR. The charts use two status colours and two neutrals, and nothing
 * else: resolved is --st-resolved because it IS that status; new issues and
 * late fixes are the validated context gray; single-series columns are the
 * neutral mark. The pairs were checked with the dataviz validator, which is
 * also why "late" is not red: red against green measured ΔE 1.9 under
 * deuteranopia in dark mode -- one colour to roughly one man in twelve.
 */
export default function DashboardPage() {
  const summary = useDashboardSummary();
  const metrics = useDashboardMetrics();
  const stream = useDashboardStream();

  return (
    <PageShell wide>
      <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
        <h1 className="text-display">Accountability dashboard</h1>
        <LiveBadge state={stream} />
      </div>

      {summary.isPending && <SkeletonTileGrid label="Loading the dashboard" />}

      {summary.isError && (
        <ErrorState
          action="Loading the dashboard"
          problem={(summary.error as ApiError)?.problem}
          onRetry={() => void summary.refetch()}
        />
      )}

      {summary.data && (
        <div className="mt-6 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 u-stagger">
          <MetricTile
            label="Currently overdue"
            emphasis
            {...(summary.data.overdueCount === null
              ? { pending: "This figure could not be computed just now. It will return on the next refresh." }
              : {
                  value: summary.data.overdueCount.toLocaleString("en-IN"),
                  trend: "Breached and still on the clock",
                })}
          />
          <MetricTile label="Total resolved to date" value={summary.data.totalResolved.toLocaleString("en-IN")} />
          <MetricTile
            label="Ward holding the most overdue work"
            {...(summary.data.topOverdueWard
              ? { value: String(summary.data.topOverdueWard.count), unit: `in ${summary.data.topOverdueWard.name}` }
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
      )}

      <Breaching />

      {metrics.isPending && <SkeletonTileGrid tiles={2} label="Loading the figures" />}
      {metrics.isError && (
        <ErrorState
          action="Loading the dashboard figures"
          problem={(metrics.error as ApiError)?.problem}
          onRetry={() => void metrics.refetch()}
        />
      )}
      {metrics.data && <Aggregates m={metrics.data} />}

      {/* Outside the summary's block: its own request, its own failure. */}
      <DepartmentRecord />

      {metrics.data && (
        <p className="mt-8 text-meta text-ink-muted">
          Figures generated {absoluteDateTime(metrics.data.generatedAt)}. Days are calendar days in
          Ludhiana. Resolution time runs from the first report to resolution; a fix counts as on time
          when the department claimed it before the deadline.
        </p>
      )}
    </PageShell>
  );
}

function LiveBadge({ state }: { state: StreamState }) {
  if (state === "unsupported") return null;
  const live = state === "live";
  return (
    <span className="text-meta text-ink-muted inline-flex items-center gap-2" role="status">
      <span
        aria-hidden="true"
        className="inline-block rounded-full"
        style={{ width: 8, height: 8, background: live ? "var(--ink)" : "transparent", border: "1.5px solid var(--ink-muted)" }}
      />
      {live
        ? "Live: overdue figures update as they change"
        : state === "connecting"
          ? "Connecting to live updates"
          : "Live updates interrupted, reconnecting. Figures refresh every minute meanwhile."}
    </span>
  );
}

/** The live list of breaching issues, most overdue first. */
function Breaching() {
  const breaching = useBreaching();

  return (
    <section className="mt-8">
      <h2 className="text-heading">Currently breaching</h2>
      {breaching.isPending && <SkeletonTileGrid tiles={1} label="Loading the breaching list" />}
      {breaching.isError && (
        <ErrorState
          action="Loading the breaching list"
          problem={(breaching.error as ApiError)?.problem}
          onRetry={() => void breaching.refetch()}
        />
      )}
      {breaching.data && breaching.data.length === 0 && (
        <p className="mt-2 text-body">Nothing is past its deadline right now.</p>
      )}
      {breaching.data && breaching.data.length > 0 && (
        <ol className="mt-3 list-none p-0 border-t border-rule">
          {breaching.data.map((b) => (
            <li key={b.id} className="border-b border-rule py-3 flex flex-wrap items-center gap-x-5 gap-y-1">
              <TicketRef publicRef={b.publicRef} issueId={b.id} />
              <StatusRule status={b.status} overdue />
              <span className="text-dense">
                <Link href={`/issues/${b.id}`} className="text-ink underline">
                  {b.categoryName}
                </Link>{" "}
                <span className="text-ink-muted">
                  · {b.wardName}
                  {b.departmentName ? ` · ${b.departmentName}` : ""} · {reporters(b.distinctReporterCount)}
                </span>
              </span>
              <span className="ml-auto text-dense font-semibold" style={{ color: "var(--st-breached)" }}>
                {humaniseMs(b.overdueSeconds * 1000)} overdue
                {b.escalationLevel > 0 && (
                  <span className="text-meta text-ink-muted font-normal"> · escalated to level {b.escalationLevel}</span>
                )}
              </span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

/** Hours as the coarse unit a reader acts on. */
function duration(hours: number): string {
  return hours >= 48 ? `${(hours / 24).toFixed(1)} days` : `${hours.toFixed(1)} hours`;
}

function needs(m: DashboardMetrics, have: number, what: string): string {
  const more = Math.max(1, m.minimumSample - have);
  return `Needs ${more} more resolved issue${more === 1 ? "" : "s"} before ${what} means anything (${have} so far).`;
}

const FAILED = "This figure could not be computed just now. It will return on the next refresh.";

function Aggregates({ m }: { m: DashboardMetrics }) {
  const rt = m.resolutionTime;
  const sla = m.slaCompliance;

  return (
    <>
      <div className="mt-8 grid grid-cols-1 sm:grid-cols-2 gap-3 u-stagger">
        <MetricTile
          label="Median resolution time"
          {...(!rt
            ? { pending: FAILED }
            : rt.overall.medianHours === null
              ? { pending: needs(m, rt.overall.resolved, "a median") }
              : {
                  value: duration(rt.overall.medianHours).split(" ")[0],
                  unit: duration(rt.overall.medianHours).split(" ")[1],
                  trend: `Across ${rt.overall.resolved.toLocaleString("en-IN")} resolved issues, first report to resolution`,
                })}
        />
        <MetricTile
          label="SLA compliance"
          {...(!sla
            ? { pending: FAILED }
            : sla.rate === null
              ? { pending: needs(m, sla.resolved, "a compliance rate") }
              : {
                  value: `${Math.round(sla.rate * 100)}%`,
                  trend: `${sla.onTime.toLocaleString("en-IN")} of ${sla.resolved.toLocaleString("en-IN")} fixes claimed before the deadline`,
                })}
        />
      </div>

      <section className="mt-8">
        <h2 className="text-heading">Reported against resolved, last 90 days</h2>
        <p className="mt-1 text-dense text-ink-muted" style={{ maxWidth: "var(--measure-prose)" }}>
          New issues each day against issues resolved each day. Where the gray line runs above the
          green one, the backlog is growing.
        </p>
        <div className="mt-3">
          {m.reportedVsResolved ? (
            <TrendChart
              title="New issues and resolved issues per day, last 90 days"
              series={[
                { key: "reported", label: "New issues", color: "var(--chart-context)" },
                { key: "resolved", label: "Resolved", color: "var(--st-resolved)" },
              ]}
              rows={m.reportedVsResolved}
            />
          ) : (
            <p className="text-dense text-ink-muted">{FAILED}</p>
          )}
        </div>
      </section>

      <div className="mt-8 grid grid-cols-1 lg:grid-cols-2 gap-8">
        <section>
          <h2 className="text-heading">Open backlog by age</h2>
          <div className="mt-3">
            {m.backlogAge ? (
              <ColumnChart
                title="Open issues by time since first report"
                valueLabel="Open issues"
                labelCaps
                columns={m.backlogAge.map((b) => ({ key: b.label, label: b.label, values: [b.open] }))}
              />
            ) : (
              <p className="text-dense text-ink-muted">{FAILED}</p>
            )}
          </div>
        </section>

        <section>
          <h2 className="text-heading">Fixes on time, last 30 days</h2>
          <div className="mt-3">
            {sla ? (
              <ColumnChart
                title="Issues resolved per day, split by whether the fix was claimed before the deadline"
                valueLabel="Resolved"
                labelEvery={7}
                parts={[
                  { label: "On time", color: "var(--st-resolved)" },
                  { label: "Late", color: "var(--chart-context)" },
                ]}
                columns={sla.trend.map((d) => ({
                  key: d.day,
                  label: shortDay(d.day),
                  values: [d.onTime, d.resolved - d.onTime],
                  detail:
                    d.resolved === 0
                      ? "Nothing resolved"
                      : `${d.onTime} of ${d.resolved} on time`,
                }))}
              />
            ) : (
              <p className="text-dense text-ink-muted">{FAILED}</p>
            )}
          </div>
        </section>
      </div>

      {rt && (
        <section className="mt-8">
          <h2 className="text-heading">Median resolution time</h2>
          <div className="mt-3 grid grid-cols-1 lg:grid-cols-2 gap-8">
            <MedianTable caption="By department" rows={rt.byDepartment} m={m} />
            <MedianTable caption="By ward" rows={rt.byWard} m={m} />
          </div>
        </section>
      )}

      {m.topClusters && m.topClusters.length > 0 && (
        <section className="mt-8">
          <h2 className="text-heading">Most-reported open issues</h2>
          <ol className="mt-3 list-none p-0 border-t border-rule">
            {m.topClusters.map((c) => (
              <li key={c.id} className="border-b border-rule py-3 flex flex-wrap items-center gap-x-5 gap-y-1">
                <TicketRef publicRef={c.publicRef} issueId={c.id} />
                <StatusRule status={c.status} />
                <Link href={`/issues/${c.id}`} className="text-dense text-ink underline">
                  {c.categoryName}
                </Link>
                <span className="text-dense text-ink-muted">{c.wardName}</span>
                <span className="ml-auto text-dense">
                  <strong>{reporters(c.distinctReporterCount)}</strong>
                  <span className="text-ink-muted"> · {c.reportCount} reports</span>
                </span>
              </li>
            ))}
          </ol>
        </section>
      )}
    </>
  );
}

function MedianTable({ caption, rows, m }: { caption: string; rows: Median[]; m: DashboardMetrics }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-dense border-collapse">
        <caption className="text-left text-meta text-ink-muted pb-2">{caption}</caption>
        <thead>
          <tr className="border-b border-rule text-meta text-ink-muted">
            <th scope="col" className="text-left font-medium py-2 pr-4">&nbsp;</th>
            <th scope="col" className="text-right font-medium py-2 px-4">Median</th>
            <th scope="col" className="text-right font-medium py-2 pl-4">Resolved</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.name} className="border-b border-rule">
              <th scope="row" className="text-left font-normal py-2 pr-4">{r.name}</th>
              <td className="text-right py-2 px-4 tabular-nums">
                {r.medianHours === null ? (
                  <span className="text-ink-muted">
                    {r.resolved === 0 ? "Nothing resolved yet" : `Needs ${m.minimumSample - r.resolved} more`}
                  </span>
                ) : (
                  duration(r.medianHours)
                )}
              </td>
              <td className="text-right py-2 pl-4 tabular-nums">{r.resolved}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
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
