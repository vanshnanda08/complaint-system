"use client";

import Link from "next/link";
import { useState } from "react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { PageShell } from "@/components/PageShell";
import { Button } from "@/components/Button";
import { EmptyState } from "@/components/EmptyState";
import { ErrorState } from "@/components/ErrorState";
import { LoadingState } from "@/components/LoadingState";
import { useAuth } from "@/lib/auth";
import { ApiError, request } from "@/lib/api";
import { keys } from "@/lib/queries";
import { absoluteDateTime, ageLabel } from "@/lib/format";
import { useSignIn } from "@/lib/signInDialog";
import type { AppNotification, NotificationPage } from "@/lib/types";

const PAGE = 50;

/**
 * Notifications (blueprint §3.13).
 *
 * Reverse-chronological ruled rows; an unread row carries a filled rule down
 * its leading edge, in ink rather than a status colour, because "unread" is
 * not a status of the issue. Each row links to where the next step is: a
 * request to verify goes to the verify screen, everything else to the issue.
 *
 * Opening a row marks that one read; "Mark all read" sends an empty body,
 * which the server reads as all. Only four transitions write here at all --
 * see NotificationService for why the staff-side steps do not.
 */
export default function NotificationsPage() {
  const { session, initialising, authed } = useAuth();
  const { openSignIn } = useSignIn();
  const qc = useQueryClient();
  const [limit, setLimit] = useState(PAGE);

  const list = useQuery({
    queryKey: [...keys.myNotifications(session?.userId), limit],
    queryFn: () =>
      authed((token) => request<NotificationPage>(`/me/notifications?limit=${limit}`, { token })),
    enabled: Boolean(session),
    staleTime: 30_000,
    placeholderData: keepPreviousData,
  });

  const markRead = useMutation({
    mutationFn: (ids: number[] | null) =>
      authed((token) =>
        request<{ unread: number }>("/me/notifications/read", {
          method: "POST",
          token,
          body: ids ? { ids } : {},
        }),
      ),
    // The list and the sidebar badge share the ["me", "notifications", user]
    // prefix, so one invalidation refreshes both.
    onSettled: () => void qc.invalidateQueries({ queryKey: keys.myNotifications(session?.userId) }),
  });

  if (initialising) {
    return (
      <PageShell>
        <LoadingState label="Checking your session" />
      </PageShell>
    );
  }

  if (!session) {
    return (
      <PageShell>
        <h1 className="text-display">Notifications</h1>
        <EmptyState
          message="Sign in to see updates on the problems you reported."
          actionLabel="Sign in"
          onAction={openSignIn}
        />
      </PageShell>
    );
  }

  const data = list.data;

  return (
    <PageShell wide>
      <div className="flex flex-wrap items-end gap-4">
        <h1 className="text-display">Notifications</h1>
        {data && data.unread > 0 && (
          <span className="ml-auto">
            <Button variant="secondary" onClick={() => markRead.mutate(null)} disabled={markRead.isPending}>
              Mark all read
            </Button>
          </span>
        )}
      </div>

      {list.isPending && <LoadingState label="Loading your notifications" />}

      {list.isError && (
        <ErrorState
          action="Loading your notifications"
          problem={(list.error as ApiError)?.problem}
          onRetry={() => void list.refetch()}
        />
      )}

      {data && data.items.length === 0 && <EmptyState message="Nothing new." />}

      {data && data.items.length > 0 && (
        <>
          <p className="mt-4 text-meta text-ink-muted">
            {data.unread === 0 ? "All read" : `${data.unread} unread`} · {data.total} in total
          </p>
          <ol className="mt-2 list-none p-0 border-t border-rule">
            {data.items.map((n) => (
              <Row key={n.id} n={n} onOpen={() => !n.readAt && markRead.mutate([n.id])} />
            ))}
          </ol>
          {data.items.length < data.total && (
            <div className="mt-4">
              <Button variant="secondary" onClick={() => setLimit((l) => l + PAGE)} disabled={list.isFetching}>
                Show older
              </Button>
            </div>
          )}
        </>
      )}
    </PageShell>
  );
}

function hrefFor(n: AppNotification): string | null {
  if (!n.issueId) return null;
  return n.type === "VERIFY_REQUESTED" ? `/me/verify/${n.issueId}` : `/issues/${n.issueId}`;
}

function Row({ n, onOpen }: { n: AppNotification; onOpen: () => void }) {
  const unread = !n.readAt;
  const href = hrefFor(n);

  const body = (
    <>
      <div className="flex flex-wrap items-baseline gap-x-4">
        <span className={`text-body ${unread ? "font-semibold" : ""}`}>{n.title}</span>
        <span className="ml-auto text-meta text-ink-muted" title={absoluteDateTime(n.createdAt)}>
          {ageLabel(n.createdAt)}
        </span>
      </div>
      {n.body && <p className="mt-1 text-dense text-ink-muted">{n.body}</p>}
      {unread && <span className="sr-only">Unread.</span>}
    </>
  );

  return (
    <li
      className="border-b border-rule"
      // The filled rule. Read rows keep a transparent one of the same width,
      // so marking a row read does not shift its text sideways.
      style={{ borderLeft: `4px solid ${unread ? "var(--ink)" : "transparent"}` }}
    >
      {href ? (
        <Link href={href} onClick={onOpen} className="block py-3 pl-4 pr-2 no-underline text-ink hover:bg-surface">
          {body}
        </Link>
      ) : (
        <div className="py-3 pl-4 pr-2">{body}</div>
      )}
    </li>
  );
}
