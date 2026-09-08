import Link from "next/link";
import { PageShell } from "@/components/PageShell";
import type { DashboardSummary } from "@/lib/types";

/**
 * Landing (blueprint §3.1).
 *
 * The hero is the live overdue count and it is SERVER-RENDERED, so the number
 * is in the initial HTML rather than arriving after hydration. This is the one
 * screen that does not fetch through TanStack Query, and the reason is the
 * blueprint's claim about the page: it establishes that departments are being
 * measured. A hero that renders as a blank and fills in a second later does not
 * make that claim to somebody on a slow connection, and does not make it at all
 * to a link preview or a search crawler.
 *
 * The fallback is specified too. If the overdue query is unavailable, the hero
 * shows total resolved instead -- the server returns `overdueCount: null`
 * precisely so that this page can distinguish "nothing is overdue", which is a
 * good outcome worth stating, from "we could not tell you".
 */

export const revalidate = 30;

async function loadSummary(): Promise<DashboardSummary | null> {
  const base = process.env.API_BASE ?? "http://localhost:8080/api/v1";
  try {
    const res = await fetch(`${base}/dashboard/summary`, { next: { revalidate: 30 } });
    if (!res.ok) return null;
    return (await res.json()) as DashboardSummary;
  } catch {
    // The rest of the page renders normally. Blueprint §3.1: the hero degrades,
    // the screen does not.
    return null;
  }
}

export default async function LandingPage() {
  const summary = await loadSummary();

  return (
    <PageShell>
      <section>
        {summary?.overdueCount != null && summary.overdueCount > 0 ? (
          <>
            <p className="text-display" style={{ color: "var(--st-breached)" }}>
              {summary.overdueCount.toLocaleString("en-IN")} issues are overdue
            </p>
            <p className="mt-2 text-body">
              Most of them are in{" "}
              <strong className="font-semibold">{summary.topOverdueWard?.name ?? "no single ward"}</strong>
              {summary.topOverdueDepartment && (
                <>
                  , and{" "}
                  <strong className="font-semibold">{summary.topOverdueDepartment.name}</strong> holds
                  the most
                </>
              )}
              .
            </p>
          </>
        ) : summary?.overdueCount === 0 ? (
          // A true and good statement, not an empty state.
          <>
            <p className="text-display">No overdue work</p>
            <p className="mt-2 text-body">
              {summary.totalResolved.toLocaleString("en-IN")} issues resolved to date.
            </p>
          </>
        ) : (
          <>
            <p className="text-display">
              {(summary?.totalResolved ?? 0).toLocaleString("en-IN")} issues resolved
            </p>
            <p className="mt-2 text-body text-ink-muted">
              The live overdue count is unavailable right now.
            </p>
          </>
        )}
      </section>

      {/* Two actions of equal weight (blueprint §3.1). Neither is primary. */}
      <div className="mt-8 flex flex-wrap gap-3">
        <Link
          href="/report"
          className="inline-flex items-center justify-center px-4 border border-ink bg-surface-raised text-ink text-dense no-underline"
          style={{ minHeight: "var(--hit-min)", borderRadius: "var(--radius)" }}
        >
          Report a problem
        </Link>
        <Link
          href="/dashboard"
          className="inline-flex items-center justify-center px-4 border border-ink bg-surface-raised text-ink text-dense no-underline"
          style={{ minHeight: "var(--hit-min)", borderRadius: "var(--radius)" }}
        >
          Open the dashboard
        </Link>
      </div>

      {/* Three sentences, no feature grid. */}
      <div className="mt-8" style={{ maxWidth: "var(--measure-prose)" }}>
        <p className="text-body">
          Report a civic problem in Ludhiana with a photo and a location, and the
          system groups it with everyone else who reported the same thing.
        </p>
        <p className="mt-3 text-body">
          Every issue gets a deadline based on what it is, and the clock is
          public. When a department misses one, the issue escalates and that is
          public too.
        </p>
        <p className="mt-3 text-body">
          Staff cannot mark their own work resolved. They submit it for the
          people who reported it to check.
        </p>
      </div>
    </PageShell>
  );
}
