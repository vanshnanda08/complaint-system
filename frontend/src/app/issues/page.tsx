"use client";

import { Suspense } from "react";

import { useRouter, useSearchParams } from "next/navigation";
import { PageShell } from "@/components/PageShell";
import { ReportRail } from "@/components/ReportRail";
import { EmptyState } from "@/components/EmptyState";
import { ErrorState } from "@/components/ErrorState";
import { LoadingState } from "@/components/LoadingState";
import { SkeletonIssueList } from "@/components/Skeleton";
import { IssueRow } from "@/components/IssueRow";
import { Button } from "@/components/Button";
import { useCategories, useIssues, useWards } from "@/lib/queries";
import { ISSUE_STATUSES, isOverdue, statusWord } from "@/lib/status";
import { ageLabel } from "@/lib/format";
import type { ApiError } from "@/lib/api";

/**
 * Issue index (blueprint §3.5).
 *
 * Every filter, sort and page offset lives in the URL. That is not a nicety:
 * blueprint §6 names a persona whose actual job is sharing a filtered link --
 * "open drainage issues in ward 12" is a thing a councillor's office sends to
 * a department. State held in React would make that link unshareable.
 */

const SORTS = [
  { value: "", label: "Newest" },
  { value: "priority", label: "Priority" },
  { value: "age", label: "Oldest" },
  { value: "deadline", label: "Deadline" },
  { value: "reporters", label: "Most reporters" },
];

const PAGE = 25;

function IssueIndexInner() {
  const router = useRouter();
  const params = useSearchParams();

  const status = params.get("status") ?? "";
  const category = params.get("category") ?? "";
  const ward = params.get("ward") ?? "";
  const sort = params.get("sort") ?? "";
  const offset = Number(params.get("offset") ?? 0);

  const issues = useIssues({ status, category, ward, sort, limit: PAGE, offset });
  const categories = useCategories();
  const wards = useWards();

  function setParam(key: string, value: string) {
    const next = new URLSearchParams(params.toString());
    if (value) next.set(key, value);
    else next.delete(key);
    // Any filter change resets paging. Keeping the offset would land the user
    // on page four of a three-page result and look like an empty state.
    next.delete("offset");
    router.push(`/issues?${next.toString()}`);
  }

  const filtersActive = Boolean(status || category || ward);

  return (
    <PageShell wide>
      {/*
        The report rail, and the reason this layout was chosen: somebody who
        came to report a problem is already reporting it, and somebody who came
        to look sees the list. Neither has to navigate to reach the other.

        `lg:` rather than `md:` -- the site sidebar already takes 260px at md,
        and a third column at that width leaves the list too narrow to read.
        Below lg the rail stacks ABOVE the list, so the first thing under a
        thumb on a phone is still the category grid.
      */}
      <div className="grid gap-8 lg:grid-cols-[290px_1fr] items-start">
        <div className="lg:sticky lg:top-4">
          <ReportRail />
        </div>

        <div className="min-w-0">
      <h1 className="text-display">Issues</h1>

      <div className="mt-4 flex flex-wrap gap-3">
        <Select label="Status" value={status} onChange={(v) => setParam("status", v)}
          options={[{ value: "", label: "Any status" },
            ...ISSUE_STATUSES.map((s) => ({ value: s, label: statusWord(s, "staff") }))]} />

        <Select label="Category" value={category} onChange={(v) => setParam("category", v)}
          options={[{ value: "", label: "Any category" },
            ...(categories.data ?? []).map((c) => ({ value: c.code, label: c.displayName }))]} />

        <Select label="Ward" value={ward} onChange={(v) => setParam("ward", v)}
          options={[{ value: "", label: "Any ward" },
            ...(wards.data ?? []).map((w) => ({ value: w.id, label: w.name }))]} />

        <Select label="Sort by" value={sort} onChange={(v) => setParam("sort", v)} options={SORTS} />
      </div>

      {issues.isError && (
        <ErrorState
          action="Loading issues"
          problem={(issues.error as ApiError)?.problem}
          onRetry={() => void issues.refetch()}
        />
      )}

      {/* A skeleton rather than a sentence, because this region has a known
          shape and reflowing it is what shifts the page under a reader on a
          slow connection. See the note in Skeleton.tsx. */}
      {issues.isPending && !issues.data && <SkeletonIssueList rows={8} label="Loading issues" />}

      {issues.data && issues.data.items.length === 0 && (
        filtersActive ? (
          <EmptyState message="No issues match these filters." actionLabel="Clear the filters" actionHref="/issues" />
        ) : (
          <EmptyState message="No issues yet." actionLabel="Report one" actionHref="/report" />
        )
      )}

      {issues.data && issues.data.items.length > 0 && (
        <>
          <p className="mt-5 text-meta text-ink-muted">
            {issues.data.total.toLocaleString("en-IN")} issues
            {filtersActive ? " match these filters" : ""} · showing {offset + 1}–
            {Math.min(offset + PAGE, issues.data.total)}
          </p>

          {/* u-stagger walks the rows in 28ms apart, capped so a full page does
              not make the last row wait. --i is the row's index. */}
          <div className="mt-2 border-t border-rule u-stagger">
            {issues.data.items.map((i, idx) => (
              <IssueRow
                key={i.id}
                style={{ "--i": idx } as React.CSSProperties}
                id={i.id}
                publicRef={i.publicRef}
                categoryName={i.categoryName}
                wardName={i.wardName}
                status={i.status}
                distinctReporterCount={i.distinctReporterCount}
                overdue={isOverdue(i.status, i.effectiveDeadline)}
                ageLabel={ageLabel(i.firstReportedAt)}
                effectiveDeadline={i.effectiveDeadline}
                pausedSeconds={i.pausedSeconds}
                resolvedAt={i.resolvedAt}
              />
            ))}
          </div>

          <div className="mt-5 flex items-center gap-3">
            <Button
              variant="secondary"
              disabled={offset === 0}
              onClick={() => setOffset(Math.max(0, offset - PAGE))}
            >
              Previous
            </Button>
            <Button
              variant="secondary"
              disabled={offset + PAGE >= issues.data.total}
              onClick={() => setOffset(offset + PAGE)}
            >
              Next
            </Button>
          </div>
        </>
      )}
        </div>
      </div>
    </PageShell>
  );

  function setOffset(next: number) {
    const p = new URLSearchParams(params.toString());
    if (next > 0) p.set("offset", String(next));
    else p.delete("offset");
    router.push(`/issues?${p.toString()}`);
  }
}

function Select({
  label,
  value,
  onChange,
  options,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  options: { value: string; label: string }[];
}) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-meta text-ink-muted">{label}</span>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="bg-surface-raised border border-rule px-2 text-dense text-ink"
        style={{ minHeight: 40, borderRadius: "var(--radius)" }}
      >
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  );
}

/**
 * `useSearchParams` opts the subtree into client-side rendering, so Next
 * requires a Suspense boundary around it -- without one the whole route
 * refuses to prerender. The boundary is here rather than higher up so the
 * header and page frame still render server-side while the filters resolve.
 */
export default function IssueIndexPage() {
  return (
    <Suspense
      fallback={
        <PageShell wide>
          <LoadingState label="Loading issues" />
        </PageShell>
      }
    >
      <IssueIndexInner />
    </Suspense>
  );
}
