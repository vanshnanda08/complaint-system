"use client";

import Link from "next/link";
import { Suspense, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { PageShell } from "@/components/PageShell";
import { Button } from "@/components/Button";
import { DeadlineCountdown } from "@/components/DeadlineCountdown";
import { EmptyState } from "@/components/EmptyState";
import { ErrorState } from "@/components/ErrorState";
import { LoadingState } from "@/components/LoadingState";
import { PriorityBadge } from "@/components/PriorityBadge";
import { StatusRule } from "@/components/StatusRule";
import { TextField } from "@/components/TextField";
import { useAuth } from "@/lib/auth";
import { ApiError, request } from "@/lib/api";
import { invalidateAfterModeration } from "@/lib/moderation";
import { isOverdue } from "@/lib/status";
import { useSignIn } from "@/lib/signInDialog";
import type { BoardRow, Member } from "@/lib/types";

/**
 * Assignment board (blueprint 3.16): the staff queue plus assignment.
 *
 * Rows that are acknowledged expand to a picker of the issue's department --
 * only the people the ASSIGNEE_IN_SAME_DEPARTMENT guard would accept, each
 * with how much they already hold. New rows can be ticked and acknowledged in
 * bulk.
 *
 * Every change goes through the existing transition endpoints (/acknowledge,
 * /assign), one issue per request. A bulk acknowledge is therefore N
 * transitions, not one: a refusal on one issue is reported against that row
 * with the server's reason, and does not undo the others.
 *
 * Reporter names appear here and nowhere below this role (3.16). The staff
 * queue a crew member sees has no identity on it, by construction: these come
 * from a supervisor-only endpoint, not from extra fields on the shared row.
 */
const TABS = [
  // NEW and ACKNOWLEDGED, by status. Not "nobody holds it": escalation hands
  // assigned_to up the ladder, so escalated work has a holder and still needs
  // a crew (QueueTab.TO_ASSIGN).
  { value: "to_assign", label: "To assign" },
  { value: "all", label: "All open" },
];

export default function AssignmentBoardPage() {
  return (
    <Suspense fallback={<PageShell wide><LoadingState label="Loading" /></PageShell>}>
      <Board />
    </Suspense>
  );
}

function Board() {
  const router = useRouter();
  const params = useSearchParams();
  const tab = params.get("tab") ?? "to_assign";
  const { session, initialising, authed } = useAuth();
  const { openSignIn } = useSignIn();
  const qc = useQueryClient();
  const isSupervisor = session?.role === "SUPERVISOR" || session?.role === "ADMIN";

  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [rowErrors, setRowErrors] = useState<Record<string, string>>({});
  const [summary, setSummary] = useState<string | null>(null);

  const board = useQuery({
    queryKey: ["supervisor", "board", tab, session?.userId],
    queryFn: () => authed((token) => request<BoardRow[]>(`/supervisor/queue?tab=${tab}&limit=200`, { token })),
    enabled: Boolean(isSupervisor),
    staleTime: 30_000,
  });

  // One transition per issue, in sequence: each succeeds or fails on its own.
  const bulkAck = useMutation({
    mutationFn: async (ids: string[]) => {
      const failures: Record<string, string> = {};
      let done = 0;
      for (const id of ids) {
        try {
          await authed((token) => request(`/issues/${id}/acknowledge`, { method: "POST", token, body: {} }));
          done++;
        } catch (e) {
          failures[id] = (e instanceof ApiError && e.problem?.detail) || "This one could not be acknowledged.";
        }
      }
      return { done, failures };
    },
    onSuccess: ({ done, failures }) => {
      setRowErrors(failures);
      setSelected(new Set(Object.keys(failures)));
      const failed = Object.keys(failures).length;
      setSummary(`${done} acknowledged${failed ? `, ${failed} refused — the reason is on each row` : ""}.`);
      invalidateAfterModeration(qc);
    },
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
        <h1 className="text-display">Assignment board</h1>
        <EmptyState
          message={session ? "The assignment board is for supervisors and administrators." : "Sign in with a supervisor account to assign work."}
          {...(session ? {} : { actionLabel: "Sign in", onAction: openSignIn })}
        />
      </PageShell>
    );
  }

  const rows = board.data ?? [];
  const acknowledgeable = rows.filter((b) => b.row.status === "NEW");
  const toggle = (id: string) =>
    setSelected((s) => {
      const next = new Set(s);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  return (
    <PageShell wide>
      <h1 className="text-display">Assignment board</h1>
      <p className="mt-1 text-dense text-ink-muted">Most urgent first. Acknowledge new work, then assign it to a crew member.</p>

      <div className="mt-4 flex flex-wrap gap-1 border-b border-rule">
        {TABS.map((t) => (
          <button
            key={t.value}
            type="button"
            onClick={() => {
              setSelected(new Set());
              setRowErrors({});
              setSummary(null);
              router.push(`/supervisor/queue?tab=${t.value}`);
            }}
            className="px-3 py-2 bg-transparent border-0 cursor-pointer text-dense text-ink"
            style={{
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

      {board.isPending && <LoadingState label="Loading the board" />}
      {board.isError && (
        <ErrorState action="Loading the board" problem={(board.error as ApiError)?.problem} onRetry={() => void board.refetch()} />
      )}

      {board.data && rows.length === 0 && (
        tab === "to_assign" ? (
          <EmptyState message="Nothing is waiting to be acknowledged or assigned." actionLabel="See all open work" actionHref="/supervisor/queue?tab=all" />
        ) : (
          <EmptyState message="No open work in your scope." />
        )
      )}

      {rows.length > 0 && (
        <>
          <div className="mt-4 flex flex-wrap items-center gap-3">
            <span className="text-meta text-ink-muted">
              {rows.length} issue{rows.length === 1 ? "" : "s"}
              {acknowledgeable.length > 0 && ` · ${acknowledgeable.length} new`}
            </span>
            {acknowledgeable.length > 0 && (
              <>
                <button
                  type="button"
                  className="text-meta underline bg-transparent border-0 p-0 cursor-pointer text-ink"
                  onClick={() => setSelected(new Set(acknowledgeable.map((b) => b.row.id)))}
                >
                  Select all new
                </button>
                <Button
                  variant="secondary"
                  disabled={selected.size === 0 || bulkAck.isPending}
                  onClick={() => bulkAck.mutate([...selected])}
                >
                  {bulkAck.isPending
                    ? "Acknowledging…"
                    : selected.size > 0
                      ? `Acknowledge ${selected.size} selected`
                      : "Acknowledge selected"}
                </Button>
              </>
            )}
          </div>
          {summary && (
            <p role="status" className="mt-2 text-dense">
              {summary}
            </p>
          )}

          <ol className="mt-3 list-none p-0 border-t border-rule">
            {rows.map((b) => (
              <Row
                key={b.row.id}
                b={b}
                selectable={b.row.status === "NEW"}
                selected={selected.has(b.row.id)}
                onToggle={() => toggle(b.row.id)}
                error={rowErrors[b.row.id]}
              />
            ))}
          </ol>
        </>
      )}
    </PageShell>
  );
}

/**
 * Who holds the issue, in the right words. Before a crew is assigned, a holder
 * can only have come from escalation -- the SLA ladder hands assigned_to up to
 * a department head, ward officer or the commissioner -- and calling that
 * "assigned" would hide that nobody has been given the work.
 */
function holder(b: BoardRow): string {
  if (!b.assigneeName) return "unassigned";
  const preAssignment = b.row.status === "NEW" || b.row.status === "ACKNOWLEDGED";
  return preAssignment
    ? `escalated to ${b.assigneeName} (level ${b.row.escalationLevel}), no crew assigned`
    : `assigned to ${b.assigneeName}`;
}

function reporterLine(b: BoardRow): string {
  const names = b.reporters.map((r) => r.fullName);
  if (b.anonymousReporters > 0) {
    names.push(`${b.anonymousReporters} anonymous`);
  }
  return names.length ? names.join(", ") : "No reporters on record";
}

function Row({
  b,
  selectable,
  selected,
  onToggle,
  error,
}: {
  b: BoardRow;
  selectable: boolean;
  selected: boolean;
  onToggle: () => void;
  error?: string;
}) {
  const r = b.row;
  const [open, setOpen] = useState(false);
  const overdue = isOverdue(r.status, r.effectiveDeadline);
  const assignable = r.status === "ACKNOWLEDGED";

  return (
    <li className="border-b border-rule py-3">
      <div className="flex flex-wrap items-start gap-x-4 gap-y-2">
        <div className="pt-1" style={{ width: 24 }}>
          {selectable && (
            <input
              type="checkbox"
              className="w-5 h-5"
              checked={selected}
              onChange={onToggle}
              aria-label={`Select ${r.publicRef} to acknowledge`}
            />
          )}
        </div>
        <div className="flex-1 min-w-[18rem]">
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
            <StatusRule status={r.status} overdue={overdue} audience="staff" />
            <Link href={`/staff/issues/${r.id}`} className="text-ref font-mono text-ink no-underline hover:underline">
              {r.publicRef}
            </Link>
            <PriorityBadge priority={r.priority} score={r.priorityScore} />
            <DeadlineCountdown
              status={r.status}
              effectiveDeadline={r.effectiveDeadline}
              pausedSeconds={r.pausedSeconds}
              resolvedAt={null}
            />
          </div>
          <p className="mt-1 text-body">
            {r.categoryName} · {r.wardName}
            {r.landmark ? ` · ${r.landmark}` : ""}
          </p>
          <p className="mt-1 text-meta text-ink-muted">
            Reported by {reporterLine(b)}
            {" · "}
            {holder(b)}
          </p>
          {error && (
            <p className="mt-1 text-dense" style={{ color: "var(--st-breached)" }} role="alert">
              {error}
            </p>
          )}
        </div>
        {assignable && (
          <Button variant="secondary" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
            {open ? "Close" : "Assign"}
          </Button>
        )}
      </div>
      {assignable && open && r.departmentId && (
        <Picker issueId={r.id} departmentId={r.departmentId} onDone={() => setOpen(false)} />
      )}
    </li>
  );
}

function Picker({ issueId, departmentId, onDone }: { issueId: string; departmentId: string; onDone: () => void }) {
  const { authed, session } = useAuth();
  const qc = useQueryClient();
  const [assignee, setAssignee] = useState<string | null>(null);
  const [note, setNote] = useState("");

  const members = useQuery({
    queryKey: ["supervisor", "members", departmentId, session?.userId],
    queryFn: () => authed((token) => request<Member[]>(`/supervisor/departments/${departmentId}/members`, { token })),
    staleTime: 30_000,
  });

  const assign = useMutation({
    mutationFn: () =>
      authed((token) =>
        request(`/issues/${issueId}/assign`, {
          method: "POST",
          token,
          body: { assigneeId: assignee, note: note.trim() || undefined },
        }),
      ),
    onSuccess: () => {
      invalidateAfterModeration(qc);
      onDone();
    },
  });

  return (
    <div className="mt-3 ml-10" style={{ maxWidth: 560 }}>
      {members.isPending && <LoadingState label="Loading the department" />}
      {members.isError && <ErrorState action="Loading the department" problem={(members.error as ApiError)?.problem} />}
      {members.data && members.data.length === 0 && (
        <p className="text-dense">Nobody in this department can be assigned work yet.</p>
      )}
      {members.data && members.data.length > 0 && (
        <fieldset className="border-0 p-0 m-0">
          <legend className="text-meta text-ink-muted mb-1">Assign to</legend>
          <ul className="list-none p-0 m-0 border-t border-rule">
            {members.data.map((m) => (
              <li key={m.id} className="border-b border-rule">
                <label className="flex items-center gap-3 py-2 cursor-pointer" style={{ minHeight: "var(--hit-min)" }}>
                  <input
                    type="radio"
                    name={`assignee-${issueId}`}
                    className="w-5 h-5"
                    checked={assignee === m.id}
                    onChange={() => setAssignee(m.id)}
                  />
                  <span className="text-dense">{m.fullName}</span>
                  <span className="text-meta text-ink-muted">
                    {m.role === "SUPERVISOR" ? "supervisor" : "crew"}
                    {m.wardName ? ` · ${m.wardName}` : ""} · holding {m.openAssigned}
                  </span>
                </label>
              </li>
            ))}
          </ul>
        </fieldset>
      )}
      <div className="mt-3">
        <TextField label="Note (optional)" value={note} maxLength={2000} onChange={(e) => setNote(e.target.value)} />
      </div>
      <div className="mt-3">
        <Button disabled={!assignee || assign.isPending} onClick={() => assign.mutate()}>
          {assign.isPending ? "Assigning…" : "Assign"}
        </Button>
      </div>
      {assign.isError && <ErrorState action="Assigning" problem={(assign.error as ApiError)?.problem} />}
    </div>
  );
}
