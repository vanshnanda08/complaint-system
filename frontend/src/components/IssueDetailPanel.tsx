"use client";

import Link from "next/link";
import { Photo } from "@/components/Photo";
import { StatusRule } from "@/components/StatusRule";
import { DeadlineCountdown } from "@/components/DeadlineCountdown";
import { useIssueHistory, useIssueReports } from "@/lib/queries";
import { STATUS, statusWord, isOverdue } from "@/lib/status";
import { absoluteDateTime, metres } from "@/lib/format";
import type { PublicIssue } from "@/lib/types";

/**
 * Everything known about one issue, in a panel rather than on a page.
 *
 * WHO SEES THE ENGINE. `technical` decides whether the clustering figures
 * appear -- the extent against its cap, the positional uncertainty. They are
 * facts about how the engine grouped reports, not facts about somebody's
 * street, and a citizen looking for their pothole does not need to be told that
 * a report landing 25 m away would have merged into it. Putting them in front
 * of everybody turns a simple panel into an instrument readout, which is the
 * fastest way to lose a reader who is not technical.
 *
 * Staff and officers do need them: it is how you tell one pothole from two.
 *
 * THE HISTORY IS NOT DECORATION. Since supervisors gained the close, the audit
 * trail is what keeps the accountability property -- a department head closing
 * their own department's breaches is recorded here by name, on a panel that
 * needs no account to read. It is fetched, not faked.
 */
export function IssueDetailPanel({
  issue,
  technical = false,
  onClose,
}: {
  issue: PublicIssue;
  technical?: boolean;
  onClose?: () => void;
}) {
  const history = useIssueHistory(issue.id);
  // The photo and the landmark live on the REPORT, not the issue -- an issue
  // is a cluster of reports and has no single photo of its own. The first
  // report is the one that created it, so its photo is the one to show.
  const reports = useIssueReports(issue.id);
  const first = reports.data?.[0];
  const overdue = isOverdue(issue.status, issue.effectiveDeadline);

  return (
    <aside
      className="bg-surface-raised border border-rule-strong p-4 flex flex-col gap-3 u-rise"
      style={{ borderRadius: "var(--radius)" }}
      aria-label={`Details for ${issue.publicRef}`}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="text-heading">{issue.categoryName}</h3>
          <p className="text-ref font-mono text-ink-faint">{issue.publicRef}</p>
        </div>
        {onClose && (
          <button
            type="button"
            onClick={onClose}
            aria-label="Close details"
            className="text-ink-faint hover:text-ink bg-transparent border-0 cursor-pointer px-2 py-1 rounded leading-none"
            style={{ fontSize: 19 }}
          >
            ×
          </button>
        )}
      </div>

      <StatusRule status={issue.status} overdue={overdue} audience="public" />

      <Photo
        src={first?.photoUrl}
        alt={`${issue.categoryName} reported at ${issue.wardName}`}
        width={640}
        height={480}
        className="w-full h-auto"
        style={{ borderRadius: "var(--radius-token-sm)" }}
      />

      <dl className="grid gap-0 border-t border-rule">
        <Row k="Ward" v={issue.wardName} />
        {first?.landmark && <Row k="Landmark" v={first.landmark} />}
        {issue.departmentName && <Row k="Department" v={issue.departmentName} />}
        <Row
          k="Deadline"
          v={
            <DeadlineCountdown
              status={issue.status}
              effectiveDeadline={issue.effectiveDeadline}
              pausedSeconds={issue.pausedSeconds}
              resolvedAt={issue.resolvedAt}
            />
          }
        />
        <Row
          k="Reported by"
          v={
            issue.distinctReporterCount === 1
              ? "1 person"
              : `${issue.distinctReporterCount} people, tracked as one problem`
          }
        />
        {technical && (
          <>
            <Row
              k="Extent"
              v={`${metres(issue.clusterExtentM)} of a ${metres(issue.clusterExtentCapM)} cap`}
            />
            <Row k="Merge radius" v={metres(issue.mergeRadiusM)} />
            <Row k="Uncertainty" v={`σ ${metres(issue.positionalUncertaintyM)}`} />
          </>
        )}
      </dl>

      <div>
        <p className="text-key">History</p>
        {history.isPending && <p className="text-dense text-ink-muted mt-1">Loading…</p>}
        {history.data && history.data.length === 0 && (
          <p className="text-dense text-ink-muted mt-1">Reported, and nothing since.</p>
        )}
        {history.data && history.data.length > 0 && (
          <ol className="mt-2 flex flex-col gap-2.5">
            {history.data.map((e, i) => (
              <li key={i} className="grid gap-2.5" style={{ gridTemplateColumns: "10px 1fr" }}>
                <span
                  aria-hidden="true"
                  className="block mt-1.5"
                  style={{
                    width: 9,
                    height: 9,
                    borderRadius: 999,
                    background: STATUS[e.toStatus].color,
                  }}
                />
                <div className="min-w-0">
                  <p className="text-dense">{statusWord(e.toStatus, "staff")}</p>
                  {/* Role, never name (blueprint §9) -- the actor's identity is
                      a staff member's, and the public record shows what they
                      are rather than who. */}
                  <p className="text-meta text-ink-faint">
                    {e.actorRole.toLowerCase()} · {absoluteDateTime(e.createdAt)}
                  </p>
                  {e.note && <p className="text-meta text-ink-muted mt-0.5">{e.note}</p>}
                </div>
              </li>
            ))}
          </ol>
        )}
      </div>

      <Link
        href={`/issues/${issue.id}`}
        className="text-dense underline text-ink no-underline hover:underline"
      >
        Open the full record →
      </Link>
    </aside>
  );
}

function Row({ k, v }: { k: string; v: React.ReactNode }) {
  return (
    <div
      className="grid gap-3 py-1.5 border-b border-rule"
      style={{ gridTemplateColumns: "7.5em 1fr" }}
    >
      <dt className="text-key pt-0.5">{k}</dt>
      <dd className="m-0 text-dense text-ink-muted min-w-0">{v}</dd>
    </div>
  );
}
