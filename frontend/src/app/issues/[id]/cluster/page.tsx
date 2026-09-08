"use client";

import Link from "next/link";
import { use, useState } from "react";
import { PageShell } from "@/components/PageShell";
import { MapCanvas } from "@/components/MapCanvas";
import { TicketRef } from "@/components/TicketRef";
import { ErrorState } from "@/components/ErrorState";
import { LoadingState } from "@/components/LoadingState";
import { useCategories, useIssue, useIssueReports } from "@/lib/queries";
import { absoluteDateTime, metres } from "@/lib/format";
import type { ClusterDecision, PublicReport } from "@/lib/types";
import type { ApiError } from "@/lib/api";

/**
 * Cluster inspector (blueprint §3.7), read-only.
 *
 * The most important screen in the project after the composer, because it is
 * the one that makes the clustering contribution visible in a single glance.
 *
 * FIVE LAYERS, drawn in the blueprint's order:
 *   1. each member report as a pin at its own coordinates
 *   2. a translucent circle per report, radius = that report's gpsAccuracyM
 *   3. the centroid as a distinct crosshair
 *   4. a dashed circle at the current effective merge radius
 *   5. a dotted circle at the extent cap, maxExtentMultiplier x mergeRadiusM
 *
 * The cap comes from GET /api/v1/categories, not from a constant here. That is
 * standing rule 1 reaching the frontend: the multiplier is configuration
 * (DD-001), it was swept empirically, and a hardcoded 2.0 would stop agreeing
 * with the engine the first time somebody changed it.
 */

/** Staff-facing vocabulary. Correct here: for staff these ARE the domain terms. */
const DECISION_LABEL: Record<ClusterDecision, string> = {
  NEW_ISSUE: "New issue",
  MERGED: "Merged",
  MERGED_LOW_CONF: "Merged, low confidence",
  SPLIT_LOW_CONF: "Split, low confidence",
  SPLIT_EXTENT_CAPPED: "Split, extent cap reached",
  MANUAL: "Placed by a supervisor",
};

/** The decisions a supervisor is asked to look at. */
const FLAGGED: ReadonlySet<ClusterDecision> = new Set([
  "MERGED_LOW_CONF",
  "SPLIT_LOW_CONF",
  "SPLIT_EXTENT_CAPPED",
]);

export default function ClusterInspectorPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const issue = useIssue(id);
  const reports = useIssueReports(id);
  const categories = useCategories();
  const [selected, setSelected] = useState<string | null>(null);

  if (issue.isPending || reports.isPending) {
    return (
      <PageShell wide>
        <LoadingState label="Loading the cluster" />
      </PageShell>
    );
  }

  if (issue.isError || reports.isError) {
    return (
      <PageShell wide>
        <ErrorState
          action="Loading the cluster"
          problem={((issue.error ?? reports.error) as ApiError)?.problem}
          onRetry={() => {
            void issue.refetch();
            void reports.refetch();
          }}
        />
      </PageShell>
    );
  }

  const i = issue.data;
  const rows = reports.data;
  const category = categories.data?.find((c) => c.code === i.categoryCode);

  // The effective merge radius in force for the most recent decision. Falls
  // back to the category's base radius for a single-report issue, which has
  // not had a merge decision made against it yet.
  const effectiveRadius =
    [...rows].reverse().find((r) => r.effectiveRadiusM != null)?.effectiveRadiusM ??
    category?.mergeRadiusM ??
    i.mergeRadiusM;

  const singleReport = rows.length === 1;

  return (
    <PageShell wide>
      <p className="text-meta">
        <Link href={`/issues/${i.id}`} className="text-ink underline">
          ← Back to the issue
        </Link>
      </p>

      <div className="mt-2 flex flex-wrap items-center gap-4">
        <TicketRef publicRef={i.publicRef} issueId={i.id} />
        <span className="text-dense">{i.categoryName}</span>
      </div>

      <h1 className="mt-2 text-display">How these reports were grouped</h1>

      {/* The header line blueprint §3.7 asks for: extent, cap, and sigma. This is
          the adaptive-radius argument made concrete -- a twenty-report cluster
          has a tighter uncertainty than a two-report one, visibly. */}
      <p className="mt-2 text-body" style={{ maxWidth: "var(--measure-prose)" }}>
        Extent <strong>{metres(i.clusterExtentM)}</strong> of a{" "}
        <strong>{metres(i.clusterExtentCapM)}</strong> cap · positional
        uncertainty <strong>{metres(i.positionalUncertaintyM)}</strong> · merge
        radius in force <strong>{metres(effectiveRadius)}</strong>
      </p>

      <div className="mt-5 grid gap-5" style={{ gridTemplateColumns: "minmax(0, 1fr)" }}>
        <MapCanvas
          center={{ lat: i.lat, lng: i.lng }}
          zoom={18}
          height={420}
          fitToMarkers
          markers={[
            // Layer 1 and 3. Reports as dots, centroid as a crosshair.
            ...rows.map((r) => ({
              id: r.id,
              lat: r.lat,
              lng: r.lng,
              kind: "report" as const,
              color: selected === r.id ? "var(--st-active)" : "#14181A",
              label: `Report ${r.sequence}`,
              onClick: () => setSelected(r.id),
            })),
            {
              id: "centroid",
              lat: i.lat,
              lng: i.lng,
              kind: "centroid" as const,
              color: "var(--st-active)",
              label: "Cluster centroid",
            },
          ]}
          circles={[
            // Layer 2: one accuracy circle per report.
            ...rows.map((r) => ({
              lat: r.lat,
              lng: r.lng,
              radiusM: r.gpsAccuracyM,
              style: "accuracy" as const,
            })),
            // Layer 4: the effective merge radius.
            { lat: i.lat, lng: i.lng, radiusM: effectiveRadius, style: "merge" as const },
            // Layer 5: the extent cap (DD-001).
            { lat: i.lat, lng: i.lng, radiusM: i.clusterExtentCapM, style: "extentCap" as const },
          ]}
        />

        <section>
          <h2 className="text-heading">
            {rows.length} report{rows.length === 1 ? "" : "s"}
          </h2>

          {singleReport ? (
            // Single-report clusters are the common case and must not look
            // broken (blueprint §3.7).
            <p className="mt-2 text-body" style={{ maxWidth: "var(--measure-prose)" }}>
              One report. Merge radius {metres(category?.mergeRadiusM ?? i.mergeRadiusM)}, no
              cluster uncertainty yet.
            </p>
          ) : (
            <p className="mt-1 text-dense text-ink-muted">
              Each row is one submission, with the decision the engine made and the
              numbers it made it from. Tap a row to highlight its pin.
            </p>
          )}

          <div className="mt-3 border-t border-rule">
            {rows.map((r) => (
              <ReportRow
                key={r.id}
                report={r}
                selected={selected === r.id}
                onSelect={() => setSelected(selected === r.id ? null : r.id)}
              />
            ))}
          </div>
        </section>
      </div>
    </PageShell>
  );
}

function ReportRow({
  report: r,
  selected,
  onSelect,
}: {
  report: PublicReport;
  selected: boolean;
  onSelect: () => void;
}) {
  const flagged = FLAGGED.has(r.clusterDecision);

  return (
    <button
      type="button"
      onClick={onSelect}
      className="w-full text-left border-b border-rule py-3 bg-transparent cursor-pointer px-0"
      style={{
        minHeight: "var(--hit-min)",
        // Selection is weight and a rule, not colour: a selection is not a status.
        borderLeft: selected ? "3px solid var(--ink)" : "3px solid transparent",
        paddingLeft: 8,
      }}
      aria-pressed={selected}
    >
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
        <span className="text-ref font-mono">#{r.sequence}</span>
        <span className="text-dense">{DECISION_LABEL[r.clusterDecision]}</span>
        {flagged && (
          // A flagged decision carries a visible marker (blueprint §3.7). Word
          // plus rule, so it survives greyscale.
          <span
            className="text-meta px-1.5"
            style={{
              color: "var(--st-pending)",
              border: "1px solid var(--st-pending)",
              borderRadius: "var(--radius-sm)",
            }}
          >
            Flagged for review
          </span>
        )}
        <span className="ml-auto text-meta text-ink-muted">{absoluteDateTime(r.createdAt)}</span>
      </div>

      <div className="mt-1 flex flex-wrap gap-x-5 text-meta text-ink-muted">
        <span>GPS accuracy {metres(r.gpsAccuracyM)}</span>
        <span>
          {r.clusterDistanceM == null
            ? "First report, no distance"
            : `${metres(r.clusterDistanceM)} from the centroid at the time`}
        </span>
        <span>
          {r.effectiveRadiusM == null
            ? "No radius in force"
            : `radius in force ${metres(r.effectiveRadiusM)}`}
        </span>
        {r.manualPin && <span>Pin placed by hand</span>}
      </div>

      {r.landmark && <p className="mt-1 text-meta text-ink-muted">{r.landmark}</p>}
    </button>
  );
}
