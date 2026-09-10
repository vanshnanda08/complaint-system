"use client";

import { Suspense } from "react";

import { useQuery } from "@tanstack/react-query";
import { useRouter, useSearchParams } from "next/navigation";
import { PageShell } from "@/components/PageShell";
import { EmptyState } from "@/components/EmptyState";
import { ErrorState } from "@/components/ErrorState";
import { LoadingState } from "@/components/LoadingState";
import { StatusRule } from "@/components/StatusRule";
import { PriorityBadge } from "@/components/PriorityBadge";
import { DeadlineCountdown } from "@/components/DeadlineCountdown";
import { useAuth } from "@/lib/auth";
import { ApiError, request } from "@/lib/api";
import { isOverdue } from "@/lib/status";
import type { QueueRow } from "@/lib/types";
import Link from "next/link";
import { useSignIn } from "@/lib/signInDialog";

/**
 * Work queue (blueprint §3.14).
 *
 * "Tell a field worker what to do next, in an order they can defend to their
 * supervisor." Sorted by priority score then deadline, which is the server's
 * ordering and not re-sorted here -- the defensible order is the one the system
 * computed, not one the client rearranged.
 *
 * Two tabs, and the scope is server-side. A staff account cannot construct a
 * URL that shows another department's work: the department comes from the
 * token, and `?tab=` only ever narrows within it.
 *
 * A table, not cards. Density over comfort for a screen somebody uses all day.
 */
const TABS = [
  { value: "mine", label: "Assigned to me" },
  { value: "unassigned", label: "Department, unassigned" },
  { value: "all", label: "All open" },
];

function StaffQueueInner() {
  const { openSignIn } = useSignIn();
  const router = useRouter();
  const params = useSearchParams();
  const { session, initialising, authed } = useAuth();
  const tab = params.get("tab") ?? "mine";

  const queue = useQuery({
    queryKey: ["staff", "queue", tab, session?.userId],
    queryFn: () =>
      authed((token) => request<QueueRow[]>(`/staff/queue?tab=${tab}&limit=100`, { token })),
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

  if (!session || session.role === "CITIZEN") {
    return (
      <PageShell>
        <h1 className="text-display">Work queue</h1>
        <EmptyState
          message="This is the municipal staff queue. Sign in with a staff account to see it."
          actionLabel="Sign in"
          onAction={openSignIn}
        />
      </PageShell>
    );
  }

  return (
    <PageShell wide>
      <h1 className="text-display">Work queue</h1>
      <p className="mt-1 text-dense text-ink-muted">
        Most urgent first: priority score, then deadline.
      </p>

      <div className="mt-4 flex flex-wrap gap-1 border-b border-rule">
        {TABS.map((t) => (
          <button
            key={t.value}
            type="button"
            onClick={() => router.push(`/staff/queue?tab=${t.value}`)}
            className="px-3 py-2 bg-transparent border-0 cursor-pointer text-dense text-ink"
            style={{
              // Selected tab is weight plus a rule, not colour.
              fontWeight: tab === t.value ? 600 : 400,
              borderBottom: tab === t.value ? "3px solid var(--ink)" : "3px solid transparent",
              marginBottom: -1,
            }}
            aria-current={tab === t.value ? "page" : undefined}
          >
            {t.label}
          </button>
        ))}
      </div>

      {queue.isPending && <LoadingState label="Loading your queue" />}

      {queue.isError && (
        <ErrorState
          action="Loading the queue"
          problem={(queue.error as ApiError)?.problem}
          onRetry={() => void queue.refetch()}
        />
      )}

      {queue.data && queue.data.length === 0 && (
        tab === "mine" ? (
          <EmptyState
            message="Nothing assigned to you."
            actionLabel="See what the department has not picked up"
            actionHref="/staff/queue?tab=unassigned"
          />
        ) : tab === "unassigned" ? (
          <EmptyState
            message="Everything open in your department is assigned."
            actionLabel="See all open work"
            actionHref="/staff/queue?tab=all"
          />
        ) : (
          <EmptyState message="No open work in your department." actionLabel="Back to the map" actionHref="/map" />
        )
      )}

      {queue.data && queue.data.length > 0 && (
        <>
          <p className="mt-4 text-meta text-ink-muted">
            {queue.data.length} issue{queue.data.length === 1 ? "" : "s"}
          </p>

          <div className="mt-2 overflow-x-auto">
            <table className="w-full border-collapse" style={{ minWidth: 720 }}>
              <caption className="sr-only">
                Open issues in your department, most urgent first
              </caption>
              <thead>
                <tr className="border-b border-rule text-left">
                  <Th>Status</Th>
                  <Th>Ticket</Th>
                  <Th>Category</Th>
                  <Th>Priority</Th>
                  <Th>Reporters</Th>
                  <Th>Deadline</Th>
                  <Th>Ward</Th>
                  <Th>Landmark</Th>
                </tr>
              </thead>
              <tbody>
                {queue.data.map((r) => {
                  const overdue = isOverdue(r.status, r.effectiveDeadline);
                  return (
                    <tr key={r.id} className="border-b border-rule align-top">
                      <Td>
                        <StatusRule status={r.status} overdue={overdue} audience="staff" />
                      </Td>
                      <Td>
                        <Link
                          href={`/staff/issues/${r.id}`}
                          className="text-ref font-mono text-ink no-underline hover:underline"
                        >
                          {r.publicRef}
                        </Link>
                      </Td>
                      <Td>{r.categoryName}</Td>
                      <Td>
                        <PriorityBadge priority={r.priority} score={r.priorityScore} />
                      </Td>
                      <Td>{r.distinctReporterCount}</Td>
                      <Td>
                        <DeadlineCountdown
                          status={r.status}
                          effectiveDeadline={r.effectiveDeadline}
                          pausedSeconds={r.pausedSeconds}
                        />
                      </Td>
                      <Td>{r.wardName}</Td>
                      <Td>{r.landmark ?? "—"}</Td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </>
      )}
    </PageShell>
  );
}

function Th({ children }: { children: React.ReactNode }) {
  return <th className="py-2 pr-4 text-meta text-ink-muted font-medium">{children}</th>;
}

function Td({ children }: { children: React.ReactNode }) {
  return <td className="py-3 pr-4 text-dense">{children}</td>;
}

/**
 * `useSearchParams` opts the subtree into client-side rendering, so Next
 * requires a Suspense boundary around it -- without one the whole route
 * refuses to prerender. The boundary is here rather than higher up so the
 * header and page frame still render server-side while the filters resolve.
 */
export default function StaffQueuePage() {
  return (
    <Suspense
      fallback={
        <PageShell wide>
          <LoadingState label="Loading your queue" />
        </PageShell>
      }
    >
      <StaffQueueInner />
    </Suspense>
  );
}
