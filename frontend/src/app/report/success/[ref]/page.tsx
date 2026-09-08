"use client";

import Link from "next/link";
import { use, useEffect, useState } from "react";
import { PageShell } from "@/components/PageShell";
import { TicketRef } from "@/components/TicketRef";
import { StatusRule } from "@/components/StatusRule";
import { CountUp } from "@/components/CountUp";
import { LoadingState } from "@/components/LoadingState";
import { useAuth } from "@/lib/auth";
import { useComposer } from "@/lib/composer";
import { request } from "@/lib/api";
import { absoluteDateTime, metres } from "@/lib/format";
import type { ClusterResult, PublicIssue } from "@/lib/types";

/**
 * Report result (blueprint §3.3).
 *
 * "Give the citizen the artifact they have never had before, and tell them
 * honestly what the clustering engine decided."
 *
 * The screen prefers the ingest response handed over from the composer, and
 * falls back to refetching by reference. That fallback is why
 * GET /public/issues/by-ref/{ref} had to exist: without it this page -- the one
 * screen a person will refresh, share and come back to -- breaks on reload.
 *
 * Blueprint §9: a citizen never sees internal vocabulary. The word "merged"
 * does not appear on this screen, and neither does "cluster". They see "you are
 * the 4th person to report this".
 */
export default function ReportSuccessPage({ params }: { params: Promise<{ ref: string }> }) {
  const { ref } = use(params);
  const { session } = useAuth();
  const { reset } = useComposer();

  // The hand-over from the composer is read as INITIAL state, not assigned by
  // an effect. It is already on the device, so there is no moment at which this
  // screen legitimately knows nothing -- rendering a loading state first and
  // correcting it would be a cascading render and a visible flicker on the one
  // screen the citizen is most likely to be staring at.
  const [result, setResult] = useState<ClusterResult | null>(() => {
    if (typeof window === "undefined") return null;
    try {
      const raw = window.sessionStorage.getItem(`ct:${ref}`);
      return raw ? (JSON.parse(raw) as ClusterResult) : null;
    } catch {
      return null;
    }
  });
  const [issue, setIssue] = useState<PublicIssue | null>(null);
  const [notFound, setNotFound] = useState(false);

  useEffect(() => {
    let live = true;

    // Refetched regardless of the hand-over, so a shared or refreshed link
    // renders fully and the page shows the issue's LIVE status rather than its
    // status at the moment of submission.
    request<PublicIssue>(`/public/issues/by-ref/${encodeURIComponent(ref)}`)
      .then((fetched) => {
        if (!live) return;
        setIssue(fetched);
        // The report is durably on the server; the held draft is no longer
        // needed. Cleared here, in an async callback, rather than in the effect
        // body -- clearing another component's state synchronously during an
        // effect is the cascading render the lint rule exists to prevent.
        reset();
      })
      .catch(() => {
        if (!live) return;
        setResult((current) => {
          if (!current) setNotFound(true);
          return current;
        });
      });

    return () => {
      live = false;
    };
    // Runs once per reference.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ref]);

  if (notFound) {
    return (
      <PageShell>
        <h1 className="text-display">That ticket number was not found</h1>
        <p className="mt-3 text-body">
          Nothing here has the reference <span className="font-mono">{ref}</span>. It
          may have been typed slightly wrong.
        </p>
        <p className="mt-4">
          <Link href="/issues" className="text-body underline text-ink">
            Search the issue index
          </Link>
        </p>
      </PageShell>
    );
  }

  if (!result && !issue) {
    return (
      <PageShell>
        <LoadingState label="Loading your ticket" />
      </PageShell>
    );
  }

  const count = result?.reportCount ?? issue?.reportCount ?? 1;
  const first = count <= 1;
  const deadline = result?.dueAt ?? issue?.effectiveDeadline;
  const distance = result?.distanceToClusterM ?? null;

  return (
    <PageShell>
      <p className="text-meta text-ink-muted">Your ticket number</p>
      <div className="mt-1">
        <TicketRef publicRef={ref} issueId={issue?.id} size="large" copyable />
      </div>

      <section className="mt-8">
        {first ? (
          <>
            <p className="text-heading">First report of this problem.</p>
            {deadline && (
              <p className="mt-2 text-body">Due by {absoluteDateTime(deadline)}.</p>
            )}
          </>
        ) : (
          <>
            <p className="flex items-baseline gap-3">
              <span className="text-body">You are the</span>
              <CountUp to={count} />
              <span className="text-body">person to report this.</span>
            </p>
            <p className="mt-2 text-body">
              {distance != null
                ? `Your report was ${metres(distance)} from the others already on this ticket. `
                : ""}
              {deadline ? `It is due by ${absoluteDateTime(deadline)}.` : ""}
            </p>
            <p className="mt-2 text-dense text-ink-muted" style={{ maxWidth: "var(--measure-prose)" }}>
              More people reporting the same problem raises its priority, which
              shortens its deadline.
            </p>
          </>
        )}
      </section>

      {issue && (
        <section className="mt-8">
          <div className="flex flex-wrap items-center gap-4">
            <StatusRule status={issue.status} />
            <span className="text-dense">{issue.categoryName}</span>
          </div>
          <p className="mt-2 text-dense text-ink-muted">
            {issue.wardName}
            {issue.departmentName ? ` · ${issue.departmentName} is responsible` : ""}
          </p>
          <p className="mt-4">
            <Link href={`/issues/${issue.id}`} className="text-body underline text-ink">
              Follow what happens to this issue
            </Link>
          </p>
        </section>
      )}

      {!session && (
        <section className="mt-8 border-t border-rule pt-4" style={{ maxWidth: "var(--measure-prose)" }}>
          <p className="text-body">
            You reported this anonymously, so nobody can ask you whether it gets
            fixed.
          </p>
          <p className="mt-2">
            <Link href="/register" className="text-body underline text-ink">
              Sign in to be asked whether this gets fixed
            </Link>
          </p>
        </section>
      )}
    </PageShell>
  );
}
