"use client";

import Link from "next/link";
import { Photo } from "@/components/Photo";
import { use } from "react";
import { PageShell } from "@/components/PageShell";
import { StatusRule } from "@/components/StatusRule";
import { TicketRef } from "@/components/TicketRef";
import { PriorityBadge } from "@/components/PriorityBadge";
import { DeadlineCountdown } from "@/components/DeadlineCountdown";
import { MapCanvas } from "@/components/MapCanvas";
import { ErrorState } from "@/components/ErrorState";
import { LoadingState } from "@/components/LoadingState";
import { useIssue, useIssueHistory, useIssueReports } from "@/lib/queries";
import { STATUS, isOverdue } from "@/lib/status";
import { absoluteDateTime, ageLabel, coordinates, metres, reporters } from "@/lib/format";
import type { ApiError } from "@/lib/api";

/**
 * Issue detail (blueprint §3.6): everything publicly known about one work item.
 *
 * The timeline shows the actor's ROLE and never a name, because reporter and
 * staff identity is not public. The server enforces that -- `PublicHistoryDto`
 * has no name and no id to render -- so this screen could not leak one if it
 * tried, which is the right place for that guarantee to live.
 */
export default function IssueDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const issue = useIssue(id);
  const reports = useIssueReports(id);
  const history = useIssueHistory(id);

  if (issue.isPending) {
    return (
      <PageShell wide>
        <LoadingState label="Loading issue" />
      </PageShell>
    );
  }

  if (issue.isError) {
    const err = issue.error as ApiError;
    // 404 renders the not-found screen with a way onward, never a redirect
    // (blueprint §3.6). A redirect would destroy the URL somebody was sent.
    return (
      <PageShell>
        {err?.status === 404 ? (
          <>
            <h1 className="text-display">No such ticket</h1>
            <p className="mt-3 text-body">
              Nothing here has that reference. It may have been typed slightly wrong.
            </p>
            <p className="mt-4">
              <Link href="/issues" className="text-body underline text-ink">
                Search the issue index
              </Link>
            </p>
          </>
        ) : (
          <ErrorState action="Loading this issue" problem={err?.problem} onRetry={() => void issue.refetch()} />
        )}
      </PageShell>
    );
  }

  const i = issue.data;
  const overdue = isOverdue(i.status, i.effectiveDeadline);

  return (
    <PageShell wide>
      <div className="flex flex-wrap items-center gap-x-5 gap-y-2">
        <TicketRef publicRef={i.publicRef} copyable />
        <StatusRule status={i.status} overdue={overdue} />
        <PriorityBadge priority={i.priority} score={i.priorityScore} />
        <span className="ml-auto">
          <DeadlineCountdown
            status={i.status}
            effectiveDeadline={i.effectiveDeadline}
            pausedSeconds={i.pausedSeconds}
            resolvedAt={i.resolvedAt}
          />
        </span>
      </div>

      <h1 className="mt-3 text-display">{i.categoryName}</h1>
      <p className="mt-1 text-body text-ink-muted">
        {i.wardName}
        {i.departmentName ? ` · ${i.departmentName}` : ""} · {ageLabel(i.firstReportedAt)}
      </p>

      <dl className="mt-5 grid gap-x-8 gap-y-2" style={{ gridTemplateColumns: "auto 1fr", maxWidth: 560 }}>
        <Fact label="Reporters">
          <Link href={`/issues/${i.id}/cluster`} className="text-ink underline">
            {reporters(i.distinctReporterCount)} across {i.reportCount} report
            {i.reportCount === 1 ? "" : "s"}
          </Link>
        </Fact>
        <Fact label="Deadline">{absoluteDateTime(i.effectiveDeadline)}</Fact>
        {i.escalationLevel > 0 && (
          <Fact label="Escalated">
            Level {i.escalationLevel} of 4
          </Fact>
        )}
        {i.reopenCount > 0 && <Fact label="Reopened">{i.reopenCount} time{i.reopenCount === 1 ? "" : "s"}</Fact>}
        <Fact label="Centroid">
          <span className="font-mono text-ref">{coordinates(i.lat, i.lng)}</span>
        </Fact>
      </dl>

      {/* Photo strip from every member report. */}
      {reports.data && reports.data.length > 0 && (
        <section className="mt-8">
          <h2 className="text-heading">Photos</h2>
          <div className="mt-3 flex gap-2 overflow-x-auto pb-2">
            {reports.data.map((r) => (
              <Photo
                key={r.id}
                src={r.photoUrl}
                alt={`Report ${r.sequence}${r.landmark ? `, ${r.landmark}` : ""}`}
                width={220}
                height={165}
                className="border border-rule shrink-0 object-cover"
                style={{ borderRadius: "var(--radius)", width: 220, height: 165 }}
              />
            ))}
          </div>
        </section>
      )}

      <section className="mt-8">
        <h2 className="text-heading">Location</h2>
        <p className="mt-1 text-dense text-ink-muted">
          One marker at the cluster centroid. The individual reports are on the{" "}
          <Link href={`/issues/${i.id}/cluster`} className="text-ink underline">
            cluster inspector
          </Link>
          .
        </p>
        <div className="mt-3">
          <MapCanvas
            center={{ lat: i.lat, lng: i.lng }}
            zoom={17}
            height={320}
            markers={[
              { id: i.id, lat: i.lat, lng: i.lng, kind: "centroid", color: STATUS[i.status].color, overdue, label: i.publicRef },
            ]}
          />
        </div>
      </section>

      {i.status === "RESOLVED" || i.resolutionPhotoUrl ? (
        <section className="mt-8">
          <h2 className="text-heading">The fix</h2>
          {i.resolutionNote && <p className="mt-2 text-body">{i.resolutionNote}</p>}
          {i.resolutionPhotoUrl && (
            <Photo
              src={i.resolutionPhotoUrl}
              alt="Photo submitted as proof the problem was fixed"
              width={480}
              height={360}
              className="mt-3 border border-rule"
              style={{ borderRadius: "var(--radius)", width: "100%", maxWidth: 480, height: "auto" }}
            />
          )}
          {i.resolvedWithoutVerification && (
            <p className="mt-3 text-dense" style={{ color: "var(--st-pending)", maxWidth: "var(--measure-prose)" }}>
              This issue closed without any citizen confirming the fix. Nobody who
              reported it had an account, so nobody could be asked. The rate at
              which this happens is published on the dashboard rather than hidden.
            </p>
          )}
        </section>
      ) : null}

      <section className="mt-8">
        <h2 className="text-heading">What has happened</h2>
        {history.isPending && <LoadingState label="Loading the timeline" />}
        {history.data && history.data.length === 0 && (
          <p className="mt-2 text-dense text-ink-muted">
            Reported, and not yet picked up by the department.
          </p>
        )}
        {history.data && history.data.length > 0 && (
          <ol className="mt-3 list-none p-0 border-t border-rule">
            {history.data.map((h, n) => (
              <li key={n} className="border-b border-rule py-3">
                <div className="flex flex-wrap items-center gap-x-4">
                  <StatusRule status={h.toStatus} audience="staff" />
                  <span className="text-meta text-ink-muted">
                    {/* Role, never a name. */}
                    by {h.actorRole.toLowerCase()}
                  </span>
                  <span className="ml-auto text-meta text-ink-muted">
                    {absoluteDateTime(h.createdAt)}
                  </span>
                </div>
                {h.note && <p className="mt-1 text-dense">{h.note}</p>}
              </li>
            ))}
          </ol>
        )}
      </section>

      <section className="mt-8">
        <h2 className="text-heading">Cluster</h2>
        <p className="mt-2 text-dense text-ink-muted" style={{ maxWidth: "var(--measure-prose)" }}>
          The reports on this issue span {metres(i.clusterExtentM)} from the
          centroid, against a cap of {metres(i.clusterExtentCapM)}. Positional
          uncertainty is {metres(i.positionalUncertaintyM)}.
        </p>
        <p className="mt-2">
          <Link href={`/issues/${i.id}/cluster`} className="text-body underline text-ink">
            See how these reports were grouped
          </Link>
        </p>
      </section>
    </PageShell>
  );
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <>
      <dt className="text-meta text-ink-muted">{label}</dt>
      <dd className="m-0 text-dense">{children}</dd>
    </>
  );
}
