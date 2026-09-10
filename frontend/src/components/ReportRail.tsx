"use client";

import { useRouter } from "next/navigation";
import { CategoryPicker } from "@/components/CategoryPicker";
import { useQuery } from "@tanstack/react-query";
import { useCategories } from "@/lib/queries";
import { request } from "@/lib/api";
import type { MyReportsPage } from "@/lib/types";
import { useComposer } from "@/lib/composer";
import { useAuth } from "@/lib/auth";
import { STATUS, statusWord } from "@/lib/status";
import Link from "next/link";

/**
 * The report rail: the reason this layout was chosen.
 *
 * The argument is that a citizen arriving to report something should already be
 * reporting it. So the first step of the composer is not behind a button on
 * another page -- it is here, open, beside the list, and tapping a category
 * both starts the report and takes you to the rest of it.
 *
 * It does not duplicate the composer. Choosing a category writes to the shared
 * ComposerProvider and navigates, so the photo, the location and the retry
 * behaviour all stay in one place -- the composer opens with step one already
 * done rather than with an empty form and a lost choice. Two copies of a form
 * that can fail mid-submit is precisely the thing not to build.
 *
 * When somebody is signed in it also carries their receipts, because "what
 * happened to the thing I reported" is the only other question a citizen has,
 * and it should not be a separate page either.
 */
export function ReportRail() {
  const router = useRouter();
  const categories = useCategories();
  const { report, patch } = useComposer();
  const { session, authed } = useAuth();

  const isCitizen = !session || session.role === "CITIZEN";
  // Four, not fifty. The rail is a reminder of what you sent, not the archive;
  // /me/reports is still there for the whole list.
  const mine = useQuery({
    queryKey: ["me", "reports", "rail"],
    enabled: !!session,
    queryFn: () => authed((token) => request<MyReportsPage>("/me/reports?limit=4", { token })),
  });

  function start(code: string) {
    patch({ categoryCode: code });
    router.push("/report");
  }

  return (
    <aside
      className="flex flex-col gap-4"
      aria-label="Report a problem"
    >
      <div>
        <h2 className="text-heading">Report a problem</h2>
        <p className="mt-1 text-dense text-ink-muted">
          Pick what it is. Your location and a photo are all we need — no account.
        </p>
      </div>

      {categories.isPending && (
        <p className="text-dense text-ink-muted">Loading categories…</p>
      )}

      {categories.data && (
        <CategoryPicker
          name="rail-category"
          categories={categories.data.map((c) => ({
            code: c.code,
            displayName: c.displayName,
          }))}
          value={report.categoryCode ?? undefined}
          onChange={start}
        />
      )}

      {session && isCitizen && (
        <div className="border-t border-rule pt-3">
          <h3 className="text-heading" style={{ fontSize: "1rem" }}>
            My reports
          </h3>
          {mine.isPending && <p className="mt-1 text-dense text-ink-muted">Loading…</p>}
          {mine.data && mine.data.items.length === 0 && (
            <p className="mt-1 text-dense text-ink-muted">
              Nothing yet. Pick a category above.
            </p>
          )}
          {mine.data && mine.data.items.length > 0 && (
            <ul className="mt-2 flex flex-col gap-2">
              {mine.data.items.map((r) => (
                <li key={r.reportId}>
                  <Link
                    href={`/issues/${r.issue.id}`}
                    className="block border border-rule px-3 py-2 no-underline text-ink u-press hover:bg-surface-sunk transition-colors duration-[var(--dur-fast)]"
                    style={{ borderRadius: "var(--radius-token-sm)" }}
                  >
                    <span className="block text-ref font-mono text-ink-faint">
                      {r.issue.publicRef}
                    </span>
                    <span className="block text-dense">{r.issue.categoryName}</span>
                    <span
                      className="block text-meta"
                      style={{ color: STATUS[r.issue.status].color }}
                    >
                      {statusWord(r.issue.status)}
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </aside>
  );
}
