"use client";

import Link from "next/link";
import { useState } from "react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { PageShell } from "@/components/PageShell";
import { Button } from "@/components/Button";
import { EmptyState } from "@/components/EmptyState";
import { ErrorState } from "@/components/ErrorState";
import { LoadingState } from "@/components/LoadingState";
import { StatusRule } from "@/components/StatusRule";
import { TicketRef } from "@/components/TicketRef";
import { ClusterThumbnail } from "@/components/ClusterThumbnail";
import { useAuth } from "@/lib/auth";
import { ApiError, request } from "@/lib/api";
import { useCategories } from "@/lib/queries";
import { ageLabel, metres, reporters } from "@/lib/format";
import { invalidateAfterModeration, reviewCause } from "@/lib/moderation";
import { useSignIn } from "@/lib/signInDialog";
import type { ModerationResult, ReviewItem, ReviewPage } from "@/lib/types";

const PAGE = 25;

/**
 * Review queue (blueprint 3.17): every issue the clustering engine flagged for
 * a person, from all three causes, each labelled with its cause in words.
 *
 * Actions per row: confirm the grouping, open the split and merge tool,
 * recategorise, or reject. Scoped server-side to the caller's department or
 * ward -- the page has no filter that could widen it.
 */
export default function ReviewQueuePage() {
  const { session, initialising, authed } = useAuth();
  const { openSignIn } = useSignIn();
  const [limit, setLimit] = useState(PAGE);
  const isSupervisor = session?.role === "SUPERVISOR" || session?.role === "ADMIN";

  const page = useQuery({
    queryKey: ["supervisor", "review", session?.userId, limit],
    queryFn: () => authed((token) => request<ReviewPage>(`/supervisor/review?limit=${limit}`, { token })),
    enabled: Boolean(isSupervisor),
    staleTime: 30_000,
    placeholderData: keepPreviousData,
  });

  if (initialising) {
    return (
      <PageShell>
        <LoadingState label="Checking your session" />
      </PageShell>
    );
  }

  if (!session || !isSupervisor) {
    return (
      <PageShell>
        <h1 className="text-display">Review queue</h1>
        <EmptyState
          message={
            session
              ? "The review queue is for supervisors and administrators."
              : "Sign in with a supervisor account to see issues waiting for review."
          }
          {...(session ? {} : { actionLabel: "Sign in", onAction: openSignIn })}
        />
      </PageShell>
    );
  }

  return (
    <PageShell wide>
      <h1 className="text-display">Review queue</h1>
      <p className="mt-1 text-dense text-ink-muted" style={{ maxWidth: "var(--measure-prose)" }}>
        Groupings the clustering engine was not sure about. Oldest first.
      </p>

      {page.isPending && <LoadingState label="Loading the review queue" />}
      {page.isError && (
        <ErrorState
          action="Loading the review queue"
          problem={(page.error as ApiError)?.problem}
          onRetry={() => void page.refetch()}
        />
      )}

      {page.data && page.data.items.length === 0 && <EmptyState message="Nothing needs review." />}

      {page.data && page.data.items.length > 0 && (
        <>
          <p className="mt-4 text-meta text-ink-muted">{page.data.total} waiting</p>
          <ol className="mt-2 list-none p-0 border-t border-rule">
            {page.data.items.map((item) => (
              <Row key={item.id} item={item} />
            ))}
          </ol>
          {page.data.items.length < page.data.total && (
            <div className="mt-4">
              <Button variant="secondary" onClick={() => setLimit((l) => l + PAGE)} disabled={page.isFetching}>
                Show more
              </Button>
            </div>
          )}
        </>
      )}
    </PageShell>
  );
}

type Panel = "recategorise" | "reject" | null;

function Row({ item }: { item: ReviewItem }) {
  const { authed } = useAuth();
  const qc = useQueryClient();
  const categories = useCategories();
  const [panel, setPanel] = useState<Panel>(null);
  const [category, setCategory] = useState("");
  const [reason, setReason] = useState("");
  const cause = reviewCause(item.reviewReason);

  const act = useMutation({
    mutationFn: (a: { path: string; body: unknown }) =>
      authed((token) => request<ModerationResult | unknown>(a.path, { method: "POST", token, body: a.body })),
    onSuccess: () => invalidateAfterModeration(qc),
  });

  const trimmedReason = reason.trim();

  return (
    <li className="border-b border-rule py-4">
      <div className="flex flex-wrap gap-4">
        <ClusterThumbnail lat={item.lat} lng={item.lng} points={item.points} capM={item.extentCapM} />

        <div className="flex-1 min-w-[16rem]">
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
            <TicketRef publicRef={item.publicRef} issueId={item.id} />
            <StatusRule status={item.status} audience="staff" />
            <span className="text-meta text-ink-muted">flagged {ageLabel(item.firstReportedAt)}</span>
          </div>
          <p className="mt-1 text-body">
            <strong>{cause.title}.</strong> {item.categoryName} · {item.wardName}
          </p>
          <p className="mt-1 text-dense text-ink-muted" style={{ maxWidth: "var(--measure-prose)" }}>
            {cause.explain}
          </p>
          <p className="mt-1 text-meta text-ink-muted">
            {reporters(item.distinctReporterCount)} across {item.reportCount} report{item.reportCount === 1 ? "" : "s"} ·
            spans {metres(item.extentM)} of a {metres(item.extentCapM)} cap
          </p>

          <div className="mt-3 flex flex-wrap gap-2">
            <Button
              variant="secondary"
              disabled={act.isPending}
              onClick={() => act.mutate({ path: `/supervisor/issues/${item.id}/confirm`, body: {} })}
            >
              Confirm grouping
            </Button>
            <Link
              href={`/supervisor/issues/${item.id}/cluster`}
              className="inline-flex items-center px-4 text-dense font-medium border border-ink text-ink no-underline u-press"
              style={{ minHeight: "var(--hit-min)", borderRadius: "var(--radius)" }}
            >
              Split or merge
            </Link>
            <Button variant="secondary" aria-expanded={panel === "recategorise"} onClick={() => setPanel(panel === "recategorise" ? null : "recategorise")}>
              Recategorise
            </Button>
            <Button variant="secondary" aria-expanded={panel === "reject"} onClick={() => setPanel(panel === "reject" ? null : "reject")}>
              Reject
            </Button>
          </div>

          {panel === "recategorise" && (
            <div className="mt-3" style={{ maxWidth: "var(--measure-prose)" }}>
              <p className="text-dense">
                Recategorising takes this issue out of its cluster: it becomes a cluster of its own in the
                new category, keeps its ticket number and reports, and is not merged with anything
                automatically. Its deadline can get shorter but never longer.
              </p>
              <div className="mt-2 flex flex-wrap items-end gap-2">
                <label className="flex flex-col gap-1 text-meta">
                  New category
                  <select
                    value={category}
                    onChange={(e) => setCategory(e.target.value)}
                    className="bg-surface-raised text-body text-ink border border-rule px-3"
                    style={{ minHeight: "var(--hit-min)", borderRadius: "var(--radius)" }}
                  >
                    <option value="">Choose…</option>
                    {categories.data
                      ?.filter((c) => c.code !== item.categoryCode)
                      .map((c) => (
                        <option key={c.code} value={c.code}>
                          {c.displayName}
                        </option>
                      ))}
                  </select>
                </label>
                <Button
                  disabled={!category || act.isPending}
                  onClick={() =>
                    act.mutate({ path: `/supervisor/issues/${item.id}/recategorise`, body: { categoryCode: category } })
                  }
                >
                  Move to this category
                </Button>
              </div>
            </div>
          )}

          {panel === "reject" && (
            <div className="mt-3" style={{ maxWidth: "var(--measure-prose)" }}>
              <label className="flex flex-col gap-1 text-meta">
                Reason, sent to everyone who reported it (at least 20 characters)
                <textarea
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                  rows={3}
                  maxLength={2000}
                  className="bg-surface-raised text-body text-ink border border-rule px-3 py-2"
                  style={{ borderRadius: "var(--radius)" }}
                />
              </label>
              <p className="mt-1 text-meta text-ink-muted">{trimmedReason.length} of 20 characters minimum</p>
              <div className="mt-2">
                <Button
                  disabled={trimmedReason.length < 20 || act.isPending}
                  onClick={() => act.mutate({ path: `/issues/${item.id}/reject`, body: { reason: trimmedReason } })}
                >
                  Reject this issue
                </Button>
              </div>
            </div>
          )}

          {act.isError && (
            <ErrorState action="That action" problem={(act.error as ApiError)?.problem} />
          )}
        </div>
      </div>
    </li>
  );
}
