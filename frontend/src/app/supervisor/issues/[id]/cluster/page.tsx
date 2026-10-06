"use client";

import Link from "next/link";
import { use, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { PageShell } from "@/components/PageShell";
import { Button } from "@/components/Button";
import { EmptyState } from "@/components/EmptyState";
import { ErrorState } from "@/components/ErrorState";
import { LoadingState } from "@/components/LoadingState";
import { MapCanvas, type MapMarker, type MapCircle } from "@/components/MapCanvas";
import { StatusRule } from "@/components/StatusRule";
import { TextField } from "@/components/TextField";
import { TicketRef } from "@/components/TicketRef";
import { useAuth } from "@/lib/auth";
import { ApiError, isUnknownTicket, request } from "@/lib/api";
import { useIssue, useIssueReports } from "@/lib/queries";
import { absoluteDateTime, metres, reporters } from "@/lib/format";
import { invalidateAfterModeration, reviewCause } from "@/lib/moderation";
import { STATUS } from "@/lib/status";
import { useSignIn } from "@/lib/signInDialog";
import type {
  ClusterGeometry,
  ModerationEntry,
  ModerationResult,
  PublicIssue,
  PublicReport,
  SplitPreview,
} from "@/lib/types";

/**
 * Split and merge (blueprint 3.18): the cluster inspector, made interactive.
 *
 * Select reports by clicking their pins, by shift-dragging a box on the map,
 * or from the list beneath it -- the list is the keyboard and touch path, and
 * the only one a screen reader can use. A selection is split into a new
 * issue; a second issue, found by its ticket reference, is merged into this
 * one.
 *
 * Both preview the result before committing, and the preview is the SERVER's
 * computation (POST .../preview), not one done here: the server is the only
 * authority on a centroid, and a client-side estimate that disagreed with
 * what was then committed would be worse than no preview at all.
 */
export default function SplitMergePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { session, initialising } = useAuth();
  const { openSignIn } = useSignIn();
  const issue = useIssue(id);
  const reports = useIssueReports(id);
  const isSupervisor = session?.role === "SUPERVISOR" || session?.role === "ADMIN";

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
        <h1 className="text-display">Split or merge</h1>
        <EmptyState
          message={session ? "Splitting and merging issues is for supervisors and administrators." : "Sign in with a supervisor account to split or merge issues."}
          {...(session ? {} : { actionLabel: "Sign in", onAction: openSignIn })}
        />
      </PageShell>
    );
  }
  if (issue.isPending || reports.isPending) {
    return (
      <PageShell wide>
        <LoadingState label="Loading the cluster" />
      </PageShell>
    );
  }
  if (issue.isError || reports.isError) {
    const err = (issue.error ?? reports.error) as ApiError;
    return (
      <PageShell>
        {isUnknownTicket(err) ? (
          <h1 className="text-display">No such ticket</h1>
        ) : (
          <ErrorState action="Loading this cluster" problem={err?.problem} onRetry={() => void issue.refetch()} />
        )}
      </PageShell>
    );
  }

  return <Tool issue={issue.data} reports={reports.data} />;
}

function Tool({ issue, reports }: { issue: PublicIssue; reports: PublicReport[] }) {
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [notice, setNotice] = useState<{ text: string; href?: string; linkText?: string } | null>(null);

  const toggle = (rid: string) =>
    setSelected((s) => {
      const next = new Set(s);
      if (next.has(rid)) next.delete(rid);
      else next.add(rid);
      return next;
    });

  const selectedIds = useMemo(() => reports.filter((r) => selected.has(r.id)).map((r) => r.id), [reports, selected]);
  const splittable = selectedIds.length > 0 && selectedIds.length < reports.length;
  const preview = useSplitPreview(issue.id, splittable ? selectedIds : null);

  const markers: MapMarker[] = [
    ...reports.map<MapMarker>((r) => ({
      id: r.id,
      lat: r.lat,
      lng: r.lng,
      kind: "report",
      // Selection is marked by the ring, not by a colour: colour means status.
      color: STATUS[issue.status].color,
      selected: selected.has(r.id),
      label: `Report ${r.sequence}${selected.has(r.id) ? ", selected" : ""}`,
      onClick: () => toggle(r.id),
    })),
    { id: "centroid", lat: issue.lat, lng: issue.lng, kind: "centroid", color: "var(--ink)", label: "Current centre" },
    ...(preview.data
      ? [
          { id: "p-remaining", lat: preview.data.remaining.lat, lng: preview.data.remaining.lng, kind: "centroid" as const, color: "var(--ink-muted)", label: "Centre after the split" },
          { id: "p-created", lat: preview.data.created.lat, lng: preview.data.created.lng, kind: "centroid" as const, color: "var(--ink-muted)", label: "Centre of the new issue" },
        ]
      : []),
  ];
  const circles: MapCircle[] = [{ lat: issue.lat, lng: issue.lng, radiusM: issue.clusterExtentCapM, style: "extentCap" }];

  const merged = issue.mergedIntoId !== null;
  const cause = issue.needsReview ? reviewCause(issue.reviewReason) : null;

  return (
    <PageShell wide>
      <div className="flex flex-wrap items-center gap-x-5 gap-y-2">
        <TicketRef publicRef={issue.publicRef} issueId={issue.id} copyable />
        <StatusRule status={issue.status} audience="staff" />
      </div>
      <h1 className="mt-3 text-display">Split or merge</h1>
      <p className="mt-1 text-body text-ink-muted">
        {issue.categoryName} · {issue.wardName} · {reporters(issue.distinctReporterCount)} across {issue.reportCount} report
        {issue.reportCount === 1 ? "" : "s"} · spans {metres(issue.clusterExtentM)} of a {metres(issue.clusterExtentCapM)} cap
      </p>
      {cause && (
        <p className="mt-2 text-dense" style={{ maxWidth: "var(--measure-prose)" }}>
          <strong>{cause.title}.</strong> {cause.explain}
        </p>
      )}

      {notice && (
        <p role="status" className="mt-4 text-body border-l-4 border-ink pl-3">
          {notice.text}{" "}
          {notice.href && (
            <Link href={notice.href} className="underline text-ink">
              {notice.linkText}
            </Link>
          )}
        </p>
      )}

      {merged ? (
        <p className="mt-6 text-body">
          This issue was merged into{" "}
          <Link href={`/supervisor/issues/${issue.mergedIntoId}/cluster`} className="underline text-ink">
            {issue.mergedIntoRef}
          </Link>
          . Its reports are there now.
        </p>
      ) : (
        <>
          <section className="mt-6">
            <MapCanvas
              center={{ lat: issue.lat, lng: issue.lng }}
              zoom={18}
              height={380}
              markers={markers}
              circles={circles}
              fitToMarkers
              onBoxSelect={(b) =>
                setSelected((s) => {
                  const next = new Set(s);
                  for (const r of reports) {
                    if (r.lat >= b.south && r.lat <= b.north && r.lng >= b.west && r.lng <= b.east) next.add(r.id);
                  }
                  return next;
                })
              }
            />
            <p className="mt-2 text-meta text-ink-muted">
              Click a pin to select it, or hold Shift and drag to select a box. The dotted ring is the
              extent cap.
            </p>
          </section>

          <section className="mt-6">
            <div className="flex flex-wrap items-baseline gap-4">
              <h2 className="text-heading">Reports</h2>
              <span className="text-meta text-ink-muted">
                {selectedIds.length} of {reports.length} selected
              </span>
              {selectedIds.length > 0 && (
                <button type="button" className="text-meta underline bg-transparent border-0 p-0 cursor-pointer text-ink" onClick={() => setSelected(new Set())}>
                  Clear selection
                </button>
              )}
            </div>
            <ul className="mt-2 list-none p-0 border-t border-rule">
              {reports.map((r) => (
                <li key={r.id} className="border-b border-rule">
                  <label className="flex items-center gap-3 py-2 cursor-pointer" style={{ minHeight: "var(--hit-min)" }}>
                    <input type="checkbox" checked={selected.has(r.id)} onChange={() => toggle(r.id)} className="w-5 h-5" />
                    <span className="text-dense">
                      Report {r.sequence}
                      {r.landmark ? `, ${r.landmark}` : ""}
                    </span>
                    <span className="text-meta text-ink-muted">
                      ±{Math.round(r.gpsAccuracyM)} m · {absoluteDateTime(r.createdAt)}
                    </span>
                  </label>
                </li>
              ))}
            </ul>
          </section>

          <SplitPanel
            issue={issue}
            selectedIds={selectedIds}
            total={reports.length}
            preview={preview.data ?? null}
            previewError={preview.error as ApiError | null}
            onDone={(r) => {
              setSelected(new Set());
              setNotice({
                text: `Split done. ${r.relatedPublicRef} was created from the selected reports.`,
                href: `/supervisor/issues/${r.relatedIssueId}/cluster`,
                linkText: `Open ${r.relatedPublicRef}`,
              });
            }}
          />

          <MergePanel
            issue={issue}
            onDone={(sourceRef) => setNotice({ text: `Merged. ${sourceRef}'s reports are now on this issue.` })}
          />
        </>
      )}

      <History issueId={issue.id} />
    </PageShell>
  );
}

function useSplitPreview(issueId: string, reportIds: string[] | null) {
  const { authed } = useAuth();
  return useQuery({
    queryKey: ["supervisor", "split-preview", issueId, reportIds],
    queryFn: () =>
      authed((token) =>
        request<SplitPreview>(`/supervisor/issues/${issueId}/split/preview`, {
          method: "POST",
          token,
          body: { reportIds },
        }),
      ),
    enabled: reportIds !== null,
    staleTime: 0,
    retry: false,
  });
}

function Geometry({ title, g }: { title: string; g: ClusterGeometry }) {
  return (
    <div className="border border-rule p-3" style={{ borderRadius: "var(--radius)" }}>
      <p className="text-dense font-semibold">{title}</p>
      <p className="mt-1 text-dense">
        {g.reportCount} report{g.reportCount === 1 ? "" : "s"} · {reporters(g.distinctReporters)}
      </p>
      <p className="text-meta text-ink-muted">
        Spans {metres(g.extentM)} · centre uncertain to ±{metres(g.positionalUncertaintyM)}
      </p>
      <p className="text-meta text-ink-muted">Clock from {absoluteDateTime(g.firstReportedAt)}</p>
    </div>
  );
}

function SplitPanel({
  issue,
  selectedIds,
  total,
  preview,
  previewError,
  onDone,
}: {
  issue: PublicIssue;
  selectedIds: string[];
  total: number;
  preview: SplitPreview | null;
  previewError: ApiError | null;
  onDone: (r: ModerationResult) => void;
}) {
  const { authed } = useAuth();
  const qc = useQueryClient();
  const [note, setNote] = useState("");

  const split = useMutation({
    mutationFn: () =>
      authed((token) =>
        request<ModerationResult>(`/supervisor/issues/${issue.id}/split`, {
          method: "POST",
          token,
          body: { reportIds: selectedIds, note: note.trim() || undefined },
        }),
      ),
    onSuccess: (r) => {
      invalidateAfterModeration(qc);
      setNote("");
      onDone(r);
    },
  });

  return (
    <section className="mt-8" style={{ maxWidth: 760 }}>
      <h2 className="text-heading">Split the selected reports into a new issue</h2>
      {selectedIds.length === 0 && <p className="mt-2 text-dense text-ink-muted">Select the reports that are a different problem.</p>}
      {selectedIds.length > 0 && selectedIds.length === total && (
        <p className="mt-2 text-dense text-ink-muted">Leave at least one report on this issue.</p>
      )}
      {previewError && <ErrorState action="Previewing the split" problem={previewError.problem} />}
      {preview && (
        <>
          <div className="mt-3 grid grid-cols-1 sm:grid-cols-2 gap-3">
            <Geometry title={`Stays on ${issue.publicRef}`} g={preview.remaining} />
            <Geometry title="Becomes a new issue" g={preview.created} />
          </div>
          <p className="mt-3 text-dense" style={{ maxWidth: "var(--measure-prose)" }}>
            No report is deleted. The selected reports move to a new issue with their photos and
            reporters, both issues are recomputed from their reports, and the split is logged
            permanently. The new issue&apos;s deadline runs from its own first report, so it may
            start overdue.
          </p>
          <div className="mt-3">
            <TextField label="Note for the log (optional)" value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
          </div>
          <div className="mt-3">
            <Button onClick={() => split.mutate()} disabled={split.isPending}>
              {split.isPending ? "Splitting" : `Split ${selectedIds.length} report${selectedIds.length === 1 ? "" : "s"} into a new issue`}
            </Button>
          </div>
          {split.isError && <ErrorState action="Splitting" problem={(split.error as ApiError)?.problem} />}
        </>
      )}
    </section>
  );
}

function MergePanel({ issue, onDone }: { issue: PublicIssue; onDone: (sourceRef: string) => void }) {
  const { authed } = useAuth();
  const qc = useQueryClient();
  const [ref, setRef] = useState("");
  const [source, setSource] = useState<PublicIssue | null>(null);
  const [note, setNote] = useState("");

  const find = useMutation({
    mutationFn: (r: string) => request<PublicIssue>(`/public/issues/by-ref/${encodeURIComponent(r)}`),
    onSuccess: (s) => setSource(s),
  });

  const preview = useQuery({
    queryKey: ["supervisor", "merge-preview", issue.id, source?.id],
    queryFn: () =>
      authed((token) =>
        request<ClusterGeometry>(`/supervisor/issues/${issue.id}/merge/preview`, {
          method: "POST",
          token,
          body: { sourceIssueId: source!.id },
        }),
      ),
    enabled: source !== null,
    staleTime: 0,
    retry: false,
  });

  const merge = useMutation({
    mutationFn: () =>
      authed((token) =>
        request<ModerationResult>(`/supervisor/issues/${issue.id}/merge`, {
          method: "POST",
          token,
          body: { sourceIssueId: source!.id, note: note.trim() || undefined },
        }),
      ),
    onSuccess: () => {
      invalidateAfterModeration(qc);
      onDone(source!.publicRef);
      setSource(null);
      setRef("");
      setNote("");
    },
  });

  const findError = find.error as ApiError | null;

  return (
    <section className="mt-8" style={{ maxWidth: 760 }}>
      <h2 className="text-heading">Merge another issue into this one</h2>
      <form
        className="mt-2 flex flex-wrap items-end gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          setSource(null);
          if (ref.trim()) find.mutate(ref.trim().toUpperCase());
        }}
      >
        <TextField
          label="Ticket reference of the duplicate"
          hint="For example CT-2026-000123. Its reports move here and it is closed, pointing to this issue."
          value={ref}
          onChange={(e) => setRef(e.target.value)}
        />
        <Button type="submit" variant="secondary" disabled={!ref.trim() || find.isPending}>
          Find
        </Button>
      </form>
      {findError &&
        (isUnknownTicket(findError) ? (
          <p className="mt-2 text-dense" style={{ color: "var(--st-breached)" }}>
            No issue has that reference.
          </p>
        ) : (
          <ErrorState action="Finding that issue" problem={findError.problem} />
        ))}

      {source && (
        <div className="mt-3">
          <p className="text-dense">
            <TicketRef publicRef={source.publicRef} issueId={source.id} /> {source.categoryName} · {source.wardName} ·{" "}
            {source.reportCount} report{source.reportCount === 1 ? "" : "s"}
          </p>
          {preview.isError && <ErrorState action="Previewing the merge" problem={(preview.error as ApiError)?.problem} />}
          {preview.data && (
            <>
              <div className="mt-3 grid grid-cols-1 sm:grid-cols-2 gap-3">
                <Geometry title={`${issue.publicRef} after the merge`} g={preview.data} />
              </div>
              <p className="mt-3 text-dense" style={{ maxWidth: "var(--measure-prose)" }}>
                No report is deleted. {source.publicRef}&apos;s reports move to {issue.publicRef}; {source.publicRef} is
                closed and its page points here, and the people who reported it are told where their
                report went. This issue keeps the higher escalation level of the two. The merge is
                logged permanently.
              </p>
              <div className="mt-3">
                <TextField label="Note for the log (optional)" value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
              </div>
              <div className="mt-3">
                <Button onClick={() => merge.mutate()} disabled={merge.isPending}>
                  {merge.isPending ? "Merging" : `Merge ${source.publicRef} into ${issue.publicRef}`}
                </Button>
              </div>
              {merge.isError && <ErrorState action="Merging" problem={(merge.error as ApiError)?.problem} />}
            </>
          )}
        </div>
      )}
    </section>
  );
}

const ACTION_WORD: Record<ModerationEntry["action"], string> = {
  CONFIRM: "Grouping confirmed",
  SPLIT: "Split",
  MERGE: "Merged",
  RECATEGORISE: "Recategorised",
};

function History({ issueId }: { issueId: string }) {
  const { authed, session } = useAuth();
  const history = useQuery({
    queryKey: ["supervisor", "moderation", issueId, session?.userId],
    queryFn: () => authed((token) => request<ModerationEntry[]>(`/supervisor/issues/${issueId}/moderation`, { token })),
    staleTime: 0,
  });

  if (!history.data || history.data.length === 0) return null;
  return (
    <section className="mt-10">
      <h2 className="text-heading">Moderation log</h2>
      <ol className="mt-2 list-none p-0 border-t border-rule">
        {history.data.map((e) => (
          <li key={e.id} className="border-b border-rule py-2 flex flex-wrap gap-x-4">
            <span className="text-dense font-semibold">{ACTION_WORD[e.action]}</span>
            <span className="text-meta text-ink-muted">by {e.actorRole.toLowerCase()}</span>
            {e.issueId !== issueId && <span className="text-meta text-ink-muted">(from the other side of this action)</span>}
            <span className="ml-auto text-meta text-ink-muted">{absoluteDateTime(e.createdAt)}</span>
            {e.note && <p className="w-full m-0 text-dense">{e.note}</p>}
          </li>
        ))}
      </ol>
    </section>
  );
}
