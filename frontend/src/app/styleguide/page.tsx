"use client";

import { useState } from "react";
import { Button } from "@/components/Button";
import { DeadlineCountdown } from "@/components/DeadlineCountdown";
import { EmptyState } from "@/components/EmptyState";
import { ErrorState } from "@/components/ErrorState";
import { LoadingState } from "@/components/LoadingState";
import { MapCanvas } from "@/components/MapCanvas";
import { PriorityBadge } from "@/components/PriorityBadge";
import { TicketRef } from "@/components/TicketRef";
import { UploadProgress } from "@/components/UploadProgress";
import { CategoryPicker } from "@/components/CategoryPicker";
import { IssueRow } from "@/components/IssueRow";
import { MetricTile } from "@/components/MetricTile";
import { StatusRule } from "@/components/StatusRule";
import { TextField } from "@/components/TextField";
import { ISSUE_STATUSES, STATUS } from "@/lib/status";

/**
 * The token system applied to real components.
 *
 * Deliberately not a swatch page. A palette looks fine as squares and fails as
 * an interface, so everything here is the component that will actually ship.
 */

function Section({ title, note, children }: { title: string; note?: string; children: React.ReactNode }) {
  return (
    <section className="mt-8">
      <h2 className="text-heading">{title}</h2>
      {note && (
        <p className="mt-1 text-dense text-ink-muted" style={{ maxWidth: "var(--measure-prose)" }}>
          {note}
        </p>
      )}
      <div className="mt-4">{children}</div>
    </section>
  );
}

/** Relative deadlines for the countdown demo, resolved at module load. */
function inHours(h: number): string {
  return new Date(Date.now() + h * 3600 * 1000).toISOString();
}

const CATEGORIES = [
  { code: "POTHOLE", displayName: "Pothole" },
  { code: "ROAD_DAMAGE", displayName: "Road Damage" },
  { code: "STREETLIGHT", displayName: "Streetlight Out" },
  { code: "GARBAGE_DUMP", displayName: "Garbage Accumulation" },
  { code: "ILLEGAL_DUMPING", displayName: "Illegal Dumping" },
  { code: "WATER_LEAK", displayName: "Water Leak" },
  { code: "DRAINAGE_BLOCK", displayName: "Blocked Drain" },
  { code: "OPEN_MANHOLE", displayName: "Open or Missing Manhole Cover" },
  { code: "STRAY_ANIMAL", displayName: "Stray Animal" },
  { code: "SIGNAGE", displayName: "Damaged or Missing Signage" },
  // Not a real code: proves the fallback renders rather than leaving a hole
  // when the database gains a category before this map does.
  { code: "NOISE_COMPLAINT", displayName: "Noise (unmapped glyph)" },
];

export default function StyleguidePage() {
  const [category, setCategory] = useState<string>("POTHOLE");

  return (
    <main className="mx-auto px-4 py-8" style={{ maxWidth: 1100 }}>
      <h1 className="text-display">Styleguide</h1>
      <p className="mt-2 text-body" style={{ maxWidth: "var(--measure-prose)" }}>
        Tokens from blueprint §1.3–1.5, applied to the components that ship.
        Colour encodes status and nothing else. Every status carries a colour,
        a shape and a word, so this page is legible in greyscale — which is the
        real test, because three statuses share one colour by design.
      </p>

      {/* ---------------------------------------------------------------- */}
      <Section
        title="Status — all nine states"
        note="Nine statuses, eight colour tokens. ACKNOWLEDGED, ASSIGNED and IN_PROGRESS
              all use --st-active: to a citizen they mean one thing, that somebody has it.
              The quarter/half/three-quarter glyph and the rule weight are what separate
              them without colour. Screenshot this in greyscale; if two rows become
              indistinguishable, the encoding is wrong."
      >
        <div className="border-t border-rule">
          {ISSUE_STATUSES.map((s) => (
            <div
              key={s}
              className="flex flex-wrap items-center gap-x-6 gap-y-2 border-b border-rule py-3"
            >
              <div style={{ minWidth: 230 }}>
                <StatusRule status={s} />
              </div>
              <code className="text-meta text-ink-muted font-mono">{s}</code>
              <span className="text-meta text-ink-muted">
                shape: {STATUS[s].shape} · glyph: {STATUS[s].glyph}
              </span>
              <span className="ml-auto text-meta text-ink-muted">
                staff reads: {STATUS[s].staffWord ?? STATUS[s].word}
              </span>
            </div>
          ))}
        </div>

        <div className="mt-6">
          <h3 className="text-meta">Overdue is an overlay, not a tenth status</h3>
          <p className="mt-1 text-dense text-ink-muted" style={{ maxWidth: "var(--measure-prose)" }}>
            The clock runs in five of the nine states. An overdue issue is still
            in whichever of them it was, so the two are shown together and the
            row reads as the true statement rather than losing half of it.
          </p>
          <div className="mt-3 flex flex-col gap-3 border-t border-rule pt-3">
            <StatusRule status="IN_PROGRESS" overdue />
            <StatusRule status="NEW" overdue />
            <StatusRule status="PENDING_VERIFICATION" audience="staff" />
          </div>
        </div>
      </Section>

      {/* ---------------------------------------------------------------- */}
      <Section
        title="Issue row"
        note="A ruled row, not a card. The hairline rule is the dominant structural device;
              cards appear only on the dashboard, where a tile is genuinely detachable."
      >
        <div className="border-t border-rule">
          <IssueRow
            id="demo-1"
            publicRef="CT-2026-000432"
            categoryName="Pothole"
            wardName="Ward 1 — Civil Lines North"
            status="IN_PROGRESS"
            distinctReporterCount={4}
            overdue
            ageLabel="Reported 6 days ago"
            effectiveDeadline={inHours(-52)}
          />
          <IssueRow
            id="demo-2"
            publicRef="CT-2026-000433"
            categoryName="Street light out"
            wardName="Ward 3 — Model Town"
            status="PENDING_VERIFICATION"
            distinctReporterCount={1}
            ageLabel="Reported 2 days ago"
            effectiveDeadline={inHours(14)}
            pausedSeconds={9 * 3600}
          />
          <IssueRow
            id="demo-3"
            publicRef="CT-2026-000434"
            categoryName="Blocked drain"
            wardName="Ward 2 — Sarabha Nagar"
            status="RESOLVED"
            distinctReporterCount={7}
            ageLabel="Reported 11 days ago"
            effectiveDeadline={inHours(-96)}
            resolvedAt={inHours(-96)}
          />
        </div>
      </Section>

      {/* ---------------------------------------------------------------- */}
      <Section
        title="Metric tile"
        note="The only card in the system, because each tile is independently screenshottable.
              The third shows the empty state: what it will show and what it needs first."
      >
        <div className="grid gap-3" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(240px, 1fr))" }}>
          <MetricTile label="Currently overdue" value="18" trend="Across 4 wards" />
          <MetricTile label="Median resolution time" value="3.2" unit="days" trend="30-day trend: −0.4 days" />
          <MetricTile
            label="Resolved without citizen verification"
            pending="Needs 20 resolved issues before this can be reported. Currently 6."
            explanation="Anonymous reporters cannot vote on whether a fix worked, so those issues close on a 72-hour timeout. Publishing the rate is the mitigation, not a footnote."
          />
        </div>
      </Section>

      {/* ---------------------------------------------------------------- */}
      <Section
        title="Category picker"
        note="Two-column grid, 48px minimum target, real radio inputs so it works from a keyboard.
              Glyphs are ink, never coloured — a category is not a status. The last entry has no
              mapped glyph and falls back rather than rendering a hole."
      >
        <div style={{ maxWidth: 560 }}>
          <CategoryPicker categories={CATEGORIES} value={category} onChange={setCategory} />
        </div>
      </Section>

      {/* ---------------------------------------------------------------- */}
      <Section title="Type scale" note="IBM Plex Sans throughout; IBM Plex Mono for references and coordinates only.">
        <div className="flex flex-col gap-3 border-t border-rule pt-4">
          <div><span className="text-display">Display 32/36</span> <span className="text-meta text-ink-muted">page titles, the breach count</span></div>
          <div><span className="text-heading">Heading 20/28</span> <span className="text-meta text-ink-muted">section heads</span></div>
          <div><span className="text-body">Body 16/26 — prose, capped at 68ch</span></div>
          <div><span className="text-dense">Dense 14/20 — tables and queues</span></div>
          <div><span className="text-meta">Meta 13/18 — timestamps and counts</span></div>
          <div><span className="text-ref font-mono">Ref 14/20 — CT-2026-000432 · 30.9300, 75.8200</span></div>
          <p className="mt-2 text-meta text-ink-muted">
            Tabular figures are on globally: 1111111111 over 8888888888 should
            align exactly. <span className="block font-mono">1111111111</span>
            <span className="block font-mono">8888888888</span>
          </p>
        </div>
      </Section>

      {/* ---------------------------------------------------------------- */}
      <Section
        title="Controls — default, disabled, focus, error"
        note="Buttons carry no colour: emphasis is weight and fill in ink, because a button is
              not a status. Tab through these — every one must show a visible ink focus ring."
      >
        <div className="flex flex-wrap items-start gap-3">
          <Button>Submit for citizen verification</Button>
          <Button variant="secondary">Report a problem</Button>
          <Button disabled>Submitting…</Button>
          <Button variant="secondary" disabled>Unavailable</Button>
        </div>

        <p className="mt-4 text-meta text-ink-muted" style={{ maxWidth: "var(--measure-prose)" }}>
          Where two actions must read as equally weighted — the verification
          screen&rsquo;s &ldquo;Fixed&rdquo; and &ldquo;Not fixed&rdquo; — both use the outlined variant.
          Making one primary would bias the measurement the whole project depends on.
        </p>
        <div className="mt-2 flex gap-3">
          <Button variant="secondary">Fixed</Button>
          <Button variant="secondary">Not fixed</Button>
        </div>

        <div className="mt-6 flex flex-col gap-5">
          <TextField label="Landmark" placeholder="Near the bus stop" hint="Optional. Helps a crew find the exact spot." />
          <TextField
            label="GPS accuracy"
            defaultValue="340 m"
            error="GPS accuracy is 340 m. Place the pin on the map instead."
          />
          <TextField label="Ward" defaultValue="Assigned automatically" disabled />
        </div>
      </Section>

      {/* ---------------------------------------------------------------- */}
      <Section
        title="Ticket reference and priority"
        note="Mono is scoped to references and coordinates only. The priority badge carries no
              colour — priority is not status, and two colour codes in one row would destroy
              the thing the palette exists to do."
      >
        <div className="flex flex-col gap-4">
          <TicketRef publicRef="CT-2026-000432" copyable />
          <div className="flex flex-wrap items-center gap-6">
            <PriorityBadge priority="LOW" score={12.5} />
            <PriorityBadge priority="MEDIUM" score={31.0} />
            <PriorityBadge priority="HIGH" score={58.4} />
            <PriorityBadge priority="CRITICAL" score={87.2} />
          </div>
          <p className="text-meta text-ink-muted">
            Hover or long-press a badge for the exact score; it is also in the accessible name,
            so it is not mouse-only.
          </p>
        </div>
      </Section>

      {/* ---------------------------------------------------------------- */}
      <Section
        title="Deadline countdown — including the pause"
        note="The SLA clock genuinely stops during PENDING_VERIFICATION, because the department is
              waiting on citizens and is not charged for the delay. A ticking countdown there would
              show a department losing time it is not losing. One shared timer drives every
              countdown on the page, not one per row."
      >
        <div className="flex flex-col gap-2 border-t border-rule pt-3">
          <DeadlineCountdown status="IN_PROGRESS" effectiveDeadline={inHours(52)} />
          <DeadlineCountdown status="NEW" effectiveDeadline={inHours(3)} />
          <DeadlineCountdown status="ASSIGNED" effectiveDeadline={inHours(-30)} />
          <DeadlineCountdown
            status="PENDING_VERIFICATION"
            effectiveDeadline={inHours(14)}
            pausedSeconds={9 * 3600}
          />
          <DeadlineCountdown status="RESOLVED" effectiveDeadline={inHours(-4)} resolvedAt={inHours(-4)} />
          <DeadlineCountdown status="REJECTED" effectiveDeadline={inHours(-4)} />
        </div>
      </Section>

      {/* ---------------------------------------------------------------- */}
      <Section
        title="Map"
        note="MapCanvas is the only module that may import Leaflet — enforced by an ESLint rule, not
              a comment. It loads via dynamic(..., { ssr: false }), so the report composer's happy
              path never downloads it. Shown here with the cluster inspector's layers: report pins,
              per-report accuracy circles, the centroid crosshair, the dashed merge radius and the
              dotted extent cap."
      >
        <MapCanvas
          center={{ lat: 30.93, lng: 75.82 }}
          zoom={18}
          height={360}
          fitToMarkers
          markers={[
            { id: "c", lat: 30.93, lng: 75.82, kind: "centroid", color: "var(--st-active)", label: "Cluster centroid" },
            { id: "r1", lat: 30.93012, lng: 75.82008, kind: "report", color: "#14181A", label: "Report 1" },
            { id: "r2", lat: 30.92991, lng: 75.81985, kind: "report", color: "#14181A", label: "Report 2" },
            { id: "r3", lat: 30.93004, lng: 75.82031, kind: "report", color: "#14181A", label: "Report 3" },
          ]}
          circles={[
            { lat: 30.93012, lng: 75.82008, radiusM: 8, style: "accuracy" },
            { lat: 30.92991, lng: 75.81985, radiusM: 14, style: "accuracy" },
            { lat: 30.93004, lng: 75.82031, radiusM: 11, style: "accuracy" },
            { lat: 30.93, lng: 75.82, radiusM: 27.5, style: "merge" },
            { lat: 30.93, lng: 75.82, radiusM: 50, style: "extentCap" },
          ]}
        />
      </Section>

      {/* ---------------------------------------------------------------- */}
      <Section
        title="The three states every screen owes"
        note="A screen without all three is not done. Empty states are a sentence plus one action,
              never an illustration. Errors say what happened and what to do — and when the server
              sent a ProblemDetail, its text is rendered verbatim, because the state machine is the
              authority and the client does not paraphrase it."
      >
        <div className="grid gap-6" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(280px, 1fr))" }}>
          <div className="border border-rule p-3" style={{ borderRadius: "var(--radius)" }}>
            <p className="text-meta text-ink-muted">Empty</p>
            <EmptyState
              message="You haven't reported anything yet."
              actionLabel="Report a problem"
              actionHref="/report"
            />
          </div>

          <div className="border border-rule p-3" style={{ borderRadius: "var(--radius)" }}>
            <p className="text-meta text-ink-muted">Loading</p>
            <LoadingState label="Loading issues" />
            <div className="mt-4">
              <p className="text-meta text-ink-muted mb-1">
                Determinate where progress is real — the photo upload
              </p>
              <UploadProgress fraction={0.62} />
            </div>
          </div>

          <div className="border border-rule p-3" style={{ borderRadius: "var(--radius)" }}>
            <p className="text-meta text-ink-muted">Error</p>
            <ErrorState
              action="Submitting the report"
              problem={{
                detail:
                  "Staff cannot resolve an issue. Submit it for citizen verification instead.",
              }}
              retryLabel="Retry submission"
              onRetry={() => {}}
            />
          </div>
        </div>
      </Section>

      <p className="mt-10 text-meta text-ink-muted">
        Two alternative token sets are in <code className="font-mono">src/app/globals.css</code> as
        commented blocks with the same structure. Swapping one in changes the whole interface;
        the font is a one-line change in <code className="font-mono">src/app/layout.tsx</code>.
      </p>
    </main>
  );
}
