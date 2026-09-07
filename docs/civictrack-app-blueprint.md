# CivicTrack — Application Blueprint

Companion to `civictrack-project-report-v2.md` (academic specification) and `civictrack-java-blueprint.md` (backend implementation). This document specifies the application surface: routes, screens, components, API contract, and design system.

Deliberately excluded: user journeys and workflow narratives. Those are report §11. Every screen here is specified as a static contract — what it shows, what it calls, what it does when there is nothing to show.

---

## 1. Design direction

### 1.1 Grounding

Three audiences, one of which dominates every constraint.

| Audience | Context | Consequence for design |
|---|---|---|
| Citizen | Outdoors, one-handed, cheap Android, Punjab daylight, poor network | Legibility at arm's length in glare. Large hit targets. Nothing decorative loads before content |
| Field staff and supervisors | Desk or depot terminal, repeated daily use | Density over comfort. Keyboard-navigable. Queues read as tables, not cards |
| Press, researchers, councillors' offices | Desktop, reading to extract a number | Numbers legible and comparable. Screenshottable |

The citizen constraint wins wherever they conflict. A screen that fails in sunlight on a ₹8,000 phone fails at the thing the project claims to do.

### 1.2 The one point of view

**Colour means status. Nothing else is coloured.**

There is no brand accent, no hero gradient, no tinted illustration. The interface is monochrome except where saturated colour carries a status value, and the status palette is regulated the way road signage is regulated. This is not minimalism as taste. It is the visual argument the product makes: the only thing worth highlighting on this screen is how overdue the work is.

Consequence for build discipline: if a component needs colour and it is not encoding status, the component is wrong. Reach for weight, size, rule, or space instead.

### 1.3 Tokens

```
/* Base — cool paper, not cream */
--ink            #14181A   /* all text, all rules */
--ink-muted      #5A6469   /* secondary text, min 4.6:1 on surface */
--surface        #F7F8F7
--surface-raised #FFFFFF
--rule           #D8DCDA   /* hairlines, table borders */

/* Status — the ONLY saturated colour in the system */
--st-new         #475569
--st-active      #1D4ED8   /* acknowledged, assigned, in progress */
--st-pending     #B45309   /* pending verification */
--st-resolved    #15803D
--st-reopened    #6D28D9
--st-breached    #B91C1C
--st-closed      #57534E
--st-rejected    #78716C
```

Every status token pairs with a shape and a word, never colour alone: breached issues carry a filled left rule plus the word "Overdue"; resolved carry an outlined rule plus "Resolved". WCAG AA contrast holds for every token on both `--surface` and `--surface-raised`.

### 1.4 Type

**IBM Plex Sans** for everything. **IBM Plex Mono** for ticket references and coordinates only.

Plex is chosen for three specific reasons, not as a default: it has genuine tabular figures, which the queues and dashboard need for column alignment; it holds legibility at 14px on low-density screens; and IBM Plex Sans Devanagari exists as a sibling family, so the Hindi and Punjabi interface in future scope does not require a typographic redesign.

The monospace is scoped tightly. A ticket reference is read aloud over a phone, compared digit by digit, and copied into a WhatsApp message. Fixed-width serves that. It is not applied to labels, metadata, or anything else.

```
Display   Plex Sans 600, 32/36, -0.02em     page titles, the breach count
Heading   Plex Sans 600, 20/28, -0.01em     section heads
Body      Plex Sans 400, 16/26                prose, max 68ch
Dense     Plex Sans 400, 14/20                tables, queues
Meta      Plex Sans 500, 13/18                timestamps, counts
Ref       Plex Mono 500, 14/20                ticket refs, lat/lng
```

Sentence case throughout. No all-caps labels, no eyebrow text above headings.

### 1.5 Layout

Single column, left-aligned, `max-width: 68ch` for prose and `100%` for tables and maps. Base unit 4px; sections separated by 32px, related items by 8px.

The dominant structural device is the **hairline rule**, not the card. Queues, timelines, and issue lists are ruled rows. Cards appear only where an item is genuinely detachable and independently actionable — the dashboard metric tiles, and nowhere else. This keeps mobile scroll density high and stops the interface reading as a card kit.

```
Mobile (360–767)              Desktop (1024+)
┌──────────────────┐          ┌────────────────────────────────┐
│ ▌CivicTrack   ≡  │          │ ▌CivicTrack   Map Issues  Dash │
├──────────────────┤          ├────────┬───────────────────────┤
│ page title       │          │ filters│ page title            │
├──────────────────┤          │ ────── │ ───────────────────── │
│ ─ row            │          │ ward   │ ─ row                 │
│ ─ row            │          │ status │ ─ row                 │
│ ─ row            │          │ cat    │ ─ row                 │
└──────────────────┘          └────────┴───────────────────────┘
Filters in a sheet            Filters persistent, left rail
```

### 1.6 Motion

One orchestrated moment in the entire application: on the report result screen and on any connected dashboard, a report count incrementing from N to N+1 animates once, 240ms, with the new value briefly carrying the status rule at full weight before settling. That single transition is the product's core claim made visible.

Nothing else moves. No entrance animations, no hover transitions on rows, no skeleton shimmer. `prefers-reduced-motion` replaces the count transition with an immediate value change.

### 1.7 Deliberately not doing

Recorded so nobody reintroduces them: no dark mode (a second palette to keep accessible, for no user need), no illustration or empty-state art, no gradient anywhere, no shadow except a 1px rule under the sticky header, no rounded corners above 4px, no icon-only buttons outside the map controls, no toast notifications for anything reversible.

---

## 2. Route map

27 routes. The Phase column maps to the build phases in `civictrack-claude-code-prompts.md`.

### Public — no authentication

| Route | Screen | Phase |
|---|---|---|
| `/` | Landing | 4 |
| `/report` | Report composer | 4 |
| `/report/success/[ref]` | Report result | 4 |
| `/map` | Issue map | 4 |
| `/issues` | Issue index | 4 |
| `/issues/[id]` | Issue detail | 4 |
| `/issues/[id]/cluster` | Cluster inspector | 4 |
| `/dashboard` | Accountability dashboard | 4 → 7 |
| `/dashboard/wards/[id]` | Ward detail | 7 |

### Authentication

| Route | Screen | Phase |
|---|---|---|
| `/login` | Sign in | 4 |
| `/register` | Create account | 4 |

### Citizen — role `CITIZEN`

| Route | Screen | Phase |
|---|---|---|
| `/me/reports` | My reports | 4 |
| `/me/verify/[id]` | Verify a fix | 6 |
| `/me/notifications` | Notifications | 6 |
| `/me/profile` | Profile | 6 |

### Field staff — role `STAFF`

| Route | Screen | Phase |
|---|---|---|
| `/staff/queue` | Work queue | 4 |
| `/staff/issues/[id]` | Work view | 4 |

### Supervisor — role `SUPERVISOR`

| Route | Screen | Phase |
|---|---|---|
| `/supervisor/queue` | Assignment board | 7 |
| `/supervisor/review` | Review queue | 7 |
| `/supervisor/issues/[id]/cluster` | Split and merge tool | 7 |
| `/supervisor/issues/[id]` | Moderation view | 7 |
| `/supervisor/analytics` | Department analytics | 7 |

### Administrator — role `ADMIN`

| Route | Screen | Phase |
|---|---|---|
| `/admin/audit` | Escalation and audit log | 7 |

Configuration screens (users, categories, wards, departments, SLA policy) are **not built**. Those tables are configured through Flyway migrations. Report §5.2 records the reasoning.

### System

| Route | Screen |
|---|---|
| `/404` | Not found |
| `/500` | Error |
| `/offline` | Network unavailable |

---

## 3. Screen specifications

Each screen lists its job, the data it needs, the API it calls, and its three degraded states. A screen without a specified empty state is an unfinished screen.

### 3.1 Landing — `/`

**Job.** Establish in one screen that this system is public and that departments are being measured, then get the visitor to either report or look at the dashboard.

**Hero.** The live count of currently overdue issues, set in Display, with the ward and department that hold the most. Not a tagline, not an illustration. A real number pulled from `GET /api/dashboard/summary`, rendered server-side so it is present in the initial HTML. If the API is unreachable the hero falls back to the count of total issues resolved to date, which is cached and always available.

**Below.** Two actions of equal weight: report a problem, open the dashboard. Then a three-sentence plain-language description of what the system does. No feature grid.

**Empty.** Before any data exists, the hero reads "No overdue work" with the total issue count beneath. That is a true and good statement, not an empty state.

**Error.** Hero falls back as above; the rest of the page renders normally.

### 3.2 Report composer — `/report`

**Job.** Photo, location, category, submitted, in under twenty seconds. This screen's performance budget is stricter than every other screen combined.

**Structure.** Three sections on one scrolling page, not a wizard. A wizard costs a tap per step and provides no benefit when there are only three inputs.

| Section | Input | Notes |
|---|---|---|
| Photo | `<input type="file" accept="image/*" capture="environment">` | Client downscales longest edge to 1600px and compresses to ~300KB before any upload begins. Preview shown at the compressed resolution so the user sees what is actually sent |
| Location | Automatic via Geolocation API, accuracy displayed in metres | When `accuracy > 150`, the automatic value is discarded and a Leaflet pin is shown for manual placement. Manual placement records `manual_pin = true` and accuracy 10 |
| Details | Category picker, optional description, optional landmark | Ten categories as a two-column grid of labelled targets, minimum 48×48px |

**Submit** is fixed to the viewport bottom on mobile and shows the compressed photo size so the user on a weak connection knows what they are about to send.

**Empty.** Not applicable.

**Loading.** Submit disables and shows upload progress as a determinate bar. Photo upload is the slow part and users abandon indeterminate spinners.

**Error.** Field-level, inline, specific. "GPS accuracy is 340 m. Place the pin on the map instead." Never a generic banner. On network failure the composed report is held in memory and the button reads "Retry submission" — no data is discarded.

**Anonymous.** Fully permitted. A single line states that an anonymous report cannot be used to verify the fix later, so the user can decide whether to sign in first. This is the one place the limitation in report §12.6 is surfaced to a citizen.

### 3.3 Report result — `/report/success/[ref]`

**Job.** Give the citizen the artifact they have never had before, and tell them honestly what the clustering engine decided.

**Content.** Ticket reference in Ref type, large, with a copy control. Then one of two statements, plain:

- New issue: "First report of this problem. Due by 14 March, 6:00 pm."
- Merged: "You are the 4th person to report this." with the distance to the existing cluster and the ticket's deadline.

The count carries the one animated transition in the system. Below: the issue's current status, its ward and department, and a link to its detail page. Then a prompt to sign in if the report was anonymous, framed as "sign in to be asked whether this gets fixed."

**Error.** If the reference does not resolve, the screen states that the ticket number was not found and offers the issue index. It does not redirect.

### 3.4 Issue map — `/map`

**Job.** Spatial browse of the whole city.

**Data.** `GET /api/issues/bbox` scoped to the current viewport, refetched on `moveend` with a 300ms debounce, capped at 500 markers. Beyond the cap the map shows a count and asks the user to zoom.

**Markers.** Status colour as fill, plus a distinct glyph per status so the map is legible in greyscale and to colour-blind users. Breached issues carry a heavier stroke.

**Filters.** Category, status, date range, ward. Persistent left rail on desktop, bottom sheet on mobile. Filter state is held in the URL so a filtered map is shareable — a councillor's office sharing "open drainage issues in ward 12" is a real use.

**Empty.** "No issues match these filters in this area." with a control to clear filters. Not an illustration.

**Loading.** Map tiles render immediately; markers arrive after. Never block the map on the marker query.

### 3.5 Issue index — `/issues`

**Job.** Tabular browse and search, for people who want to scan rather than pan.

**Rows.** Ruled, not carded. Each row: status rule and word, ticket ref, category, ward, distinct reporter count, age, deadline or overdue duration. Sortable by priority, age, deadline, reporter count.

**Mobile.** Same rows, two lines each, no horizontal scroll. Columns collapse in a fixed order: reporter count and ward move to line two, ticket ref never hides.

**Empty.** "No issues yet." with a link to report one.

### 3.6 Issue detail — `/issues/[id]`

**Job.** Everything publicly known about one work item.

**Sections.** Header with ticket ref, status, priority band, deadline or overdue duration, escalation level. Photo strip from all member reports. Location map, single marker at the centroid. Timeline of every status transition with actor role — role, never name, since reporter and staff identity is not public. Distinct reporter count with a link to the cluster inspector. If resolved, the proof photo and, where applicable, the verification outcome including whether it resolved without any citizen vote.

**Empty.** Not applicable; an issue always has at least one report.

**Error.** 404 renders the not-found screen with a search control, not a redirect.

### 3.7 Cluster inspector — `/issues/[id]/cluster`

**Job.** Make the clustering contribution visible in a single glance. This is the most important screen in the project after the report composer, and it is read-only for the public.

**Map layers**, drawn in this order:

1. Each member report as a small pin at its own coordinates
2. A translucent circle per report, radius = that report's `gps_accuracy_m`
3. The issue centroid as a distinct crosshair marker
4. A dashed circle at the current effective merge radius
5. A dotted circle at the extent cap, `max_extent_multiplier × R_cat`

**Side panel.** One ruled row per report: sequence number, submitted time, GPS accuracy, distance from the centroid at the moment it was decided, the effective radius in force at that moment, and the decision itself — `MERGED`, `MERGED_LOW_CONF`, `NEW_LOW_CONF`, or `NEW_ISSUE_EXTENT_CAP`. Rows for flagged decisions carry a visible marker.

**Header line.** Current extent, cap, and weighted positional uncertainty σ, so a viewer can see that a twenty-report cluster has a tighter radius than a two-report one. This is the adaptive-radius argument made concrete.

**Mobile.** Map on top at 60vh, panel scrolls beneath. Tapping a panel row highlights its pin and vice versa.

**Empty.** A single-report issue shows one pin, one accuracy circle, centroid coincident with the pin, and the panel line "One report. Merge radius 25 m, no cluster uncertainty yet." Single-report clusters are the common case and must not look broken.

### 3.8 Accountability dashboard — `/dashboard`

**Job.** Let anyone with no account and no context extract a number about a department in under thirty seconds.

**Metric tiles** — the one place cards are used, because each is independently screenshottable:

| Tile | Source |
|---|---|
| Median resolution time, by ward and by department | `PERCENTILE_CONT` over resolved issues |
| SLA compliance percentage, with 30-day trend | resolved-before-deadline ÷ resolved |
| Currently overdue, live | breached and clock-running |
| Reopen rate per department | reopened ÷ resolved |
| Resolved without citizen verification, per department | `resolved_without_verification` ÷ resolved |
| Open backlog age histogram | buckets over open issues |
| Reported vs resolved, daily | 90-day series |
| Top clusters by distinct reporters | ordered by `distinct_reporter_count` |

The unverified-closure tile carries one line of explanation, because it is the only metric a visitor will not immediately understand: anonymous reporters cannot vote on whether a fix worked, so those issues close on a 72-hour timeout. Publishing the rate is the mitigation described in report §12.6. A dashboard that hides its own weakest metric is the thing this project exists to argue against.

**Live.** The overdue tile and the breaching list subscribe to SSE. Everything else is fetched on load and refetched every 60 seconds.

**Empty.** Before enough data exists, each tile states what it will show and how many resolved issues it needs. Not zeros, not dashes.

**Error.** Tiles fail independently. One failed aggregate query does not blank the page.

### 3.9 Ward detail — `/dashboard/wards/[id]`

Same tiles scoped to one ward, plus the ward boundary drawn on a map with issue density, plus a recurrence list showing locations that have generated more than one issue over time. Shareable URL; this is the screen the councillor's office persona actually needs.

### 3.10 Sign in — `/login` and Create account — `/register`

Single column, 44ch, email or phone plus password. Nothing else — no social login, no marketing copy, no split-screen image.

**Error.** One message for wrong credentials, deliberately not distinguishing unknown account from wrong password. Rate-limit rejection states the wait duration.

### 3.11 My reports — `/me/reports`

Ruled rows: ticket ref, what the user reported, status, whether their report merged into an existing issue, current reporter count, deadline. A row awaiting the user's verification is marked and linked.

**Empty.** "You haven't reported anything yet." plus the report action.

### 3.12 Verify a fix — `/me/verify/[id]`

**Job.** One question, answered in two taps.

Before-and-after photos side by side on desktop, stacked on mobile, both at full width. The staff member's resolution note. Then two equally weighted controls: "Fixed" and "Not fixed." Not a primary and a secondary — weighting them differently biases the measurement the whole project depends on.

Choosing "Not fixed" reveals an optional single-line reason before submitting.

Beneath: the current tally in plain words, and the deadline after which silence counts as agreement.

**Error.** A user who has already voted sees their recorded verdict and the current tally, not an error.

### 3.13 Notifications — `/me/notifications` and Profile — `/me/profile`

Notifications: reverse-chronological ruled rows, unread carrying a filled rule, each linking to its issue. Mark-all-read control. Empty state: "Nothing new."

Profile: display name, contact, password change, notification preferences. No avatar upload.

### 3.14 Work queue — `/staff/queue`

**Job.** Tell a field worker what to do next, in an order they can defend to their supervisor.

Dense table, default sort by priority score then deadline. Columns: status, ticket ref, category, priority band, distinct reporters, deadline countdown, ward, landmark. Overdue rows carry the breached rule; rows within four hours of breach carry the pending rule.

Two tabs: assigned to me, and department unassigned. Scoped server-side to the user's department; a staff account cannot construct a URL that shows another department's work.

**Empty.** "Nothing assigned to you." plus a link to the department tab.

### 3.15 Work view — `/staff/issues/[id]`

Issue context at top, then the single action available in the current state — acknowledge, start work, or submit proof. Actions that the state machine does not permit are absent, not disabled.

The proof submission requires a photo and a note of at least twenty characters, both validated client-side before upload and again server-side. The submit control is labelled "Submit for citizen verification," never "Resolve," because staff cannot resolve. The label is the transition table made visible to the person governed by it.

**Error.** A rejected transition returns the reason from the server's `ProblemDetail` response verbatim. The state machine is the authority; the client does not paraphrase it.

### 3.16 Assignment board — `/supervisor/queue`

The staff queue plus assignment. Rows expand to a department member picker. Bulk acknowledge for multiple selected rows. Reporter identities visible here and nowhere below this role.

### 3.17 Review queue — `/supervisor/review`

Every issue with `needs_review = true`, from all three causes, each labelled with its cause: an optimistic merge near the band boundary, a cautious split in a safety-critical category, or a merge refused by the extent cap. Each row shows a thumbnail cluster preview and links to the split and merge tool.

Actions: confirm as correct, open the tool, recategorise, reject.

**Empty.** "Nothing needs review." — a legitimately good outcome and worded as one.

### 3.18 Split and merge tool — `/supervisor/issues/[id]/cluster`

The cluster inspector plus interaction. Reports are selectable individually or by dragging a box on the map. Selected reports can be split into a new issue. A second issue can be searched by ticket ref and merged into this one.

Both actions preview the resulting centroid and extent before committing, because both trigger a full recomputation and both are logged permanently. Neither deletes a report; the confirmation copy says so explicitly.

### 3.19 Moderation view — `/supervisor/issues/[id]`

Recategorise, with a stated warning that recategorisation forces the issue out of its cluster into a new one. Reject with a mandatory reason of at least twenty characters, which is sent to every reporter. Reassign ward, which triggers recomputation.

### 3.20 Department analytics — `/supervisor/analytics`

The public dashboard scoped to the supervisor's own department, plus per-staff resolution counts and median times. Internal only, never public: the project measures departments in public and individuals in private, deliberately.

### 3.21 Audit log — `/admin/audit`

Every escalation event and status transition, filterable by issue, actor, date, and level. Ruled rows, exportable as CSV. This is the screen that demonstrates the immutable audit trail claim.

### 3.22 System screens

**404.** States that the page or ticket does not exist, offers search and the issue index.

**500.** States that something failed and that the report, if one was being composed, was not lost. Offers retry.

**Offline.** Detected via `navigator.onLine`. States that a composed report is held and will be submitted when the connection returns.

---

## 4. Shared components

| Component | Used by | Contract |
|---|---|---|
| `StatusRule` | every issue surface | status → colour, glyph, word. Never colour alone. Single source of truth for the status vocabulary |
| `TicketRef` | result, detail, queues | Ref type, copy control, links to detail |
| `DeadlineCountdown` | queues, detail | Live countdown; switches to elapsed-overdue past the deadline. Pauses visibly during `PENDING_VERIFICATION`, since the clock genuinely pauses |
| `PriorityBadge` | queues, detail | Band from score. Shows the score on hover or long-press |
| `AccuracyCircle` | inspector, composer | Leaflet circle from `gps_accuracy_m` |
| `ReportPin` / `CentroidMarker` | inspector | Visually distinct; centroid is a crosshair, reports are dots |
| `IssueRow` | index, queues, review | One ruled row, responsive column collapse in a fixed order |
| `MetricTile` | dashboard, analytics | Value, label, trend, optional explanation line |
| `PhotoUploader` | composer, work view | Client compression, progress, retry, MIME check before upload |
| `EmptyState` | everywhere | Sentence plus one action. No illustration |
| `MapCanvas` | map, detail, inspector, composer | Leaflet wrapper, dynamically imported with `ssr: false` |

`MapCanvas` must be the only module that imports Leaflet. Leaflet touches `window` at module scope and will break the Next.js server render from anywhere else.

---

## 5. API surface

### Public

```
POST   /api/reports                        submit (multipart)
GET    /api/issues                         filter, sort, paginate
GET    /api/issues/bbox                    viewport-scoped, cap 500
GET    /api/issues/{id}
GET    /api/issues/{id}/reports            member reports + clustering audit
GET    /api/issues/{id}/history            status transitions
GET    /api/categories
GET    /api/wards
GET    /api/dashboard/summary
GET    /api/dashboard/wards/{id}
GET    /api/dashboard/departments
GET    /api/events                         SSE stream
```

### Authenticated

```
POST   /api/auth/register
POST   /api/auth/login
POST   /api/auth/refresh
GET    /api/me/reports
GET    /api/me/notifications
POST   /api/me/notifications/read
POST   /api/issues/{id}/verify             { verdict, reason? }
```

### Staff and supervisor

```
GET    /api/staff/queue
POST   /api/issues/{id}/acknowledge
POST   /api/issues/{id}/start
POST   /api/issues/{id}/resolve            multipart: proof photo + note
POST   /api/issues/{id}/assign             SUPERVISOR
POST   /api/issues/{id}/reject             SUPERVISOR, reason required
POST   /api/issues/{id}/recategorise       SUPERVISOR
POST   /api/issues/{id}/split              SUPERVISOR, { reportIds[] }
POST   /api/issues/{id}/merge              SUPERVISOR, { targetIssueId }
GET    /api/supervisor/review
GET    /api/admin/audit                    ADMIN
```

### Ingest response contract

The report result screen depends on this shape. It is a product contract, not a debug payload.

```json
{
  "reference": "CT-2026-000432",
  "issueId": 432,
  "clusterDecision": "MERGED",
  "distanceToClusterM": 14.2,
  "effectiveRadiusM": 27.5,
  "projectedExtentM": 18.1,
  "reportCount": 4,
  "distinctReporterCount": 4,
  "priorityBand": "HIGH",
  "dueAt": "2026-03-14T18:00:00+05:30",
  "wardName": "Ward 12",
  "departmentName": "Roads",
  "needsReview": false
}
```

Errors are RFC 7807 `ProblemDetail`. The client renders `detail` verbatim and never paraphrases a state-machine rejection.

---

## 6. Client state

**Server state:** TanStack Query. Stale time 30s for lists, 0 for issue detail, infinity for categories and wards.

**URL state:** every filter, sort, and map viewport. A filtered view must be shareable. This is not a nicety — one of the three named personas exists to share a filtered link.

**Client state:** React state only. The composed report is held in a context provider so a submission failure does not discard the photo.

**Auth:** access token in memory, refresh token in an httpOnly cookie. Never `localStorage`.

**Cache invalidation:** SSE events invalidate query keys rather than patching cache directly. The server is the authority on issue state; the client never computes a transition locally.

---

## 7. Realtime

Single `EventSource` on `/api/events`, opened once at the layout level, shared by every subscriber.

| Event | Payload | Invalidates |
|---|---|---|
| `issue.created` | id, ward, category, status | bbox and list queries |
| `issue.count` | id, reportCount, priorityBand | that issue, list queries |
| `issue.status` | id, from, to | that issue, queues, dashboard |
| `issue.breached` | id, escalationLevel, newOwnerRole | dashboard, queues |

The server sends a heartbeat comment every 25 seconds. Without it Render's proxy closes idle connections and the live demo fails silently, which is the worst possible failure mode on stage. The client reconnects with exponential backoff capped at 30 seconds and refetches on reconnect, since events during the gap are lost.

---

## 8. Budgets and quality floor

| Metric | Target | Why |
|---|---|---|
| Report composer LCP, 3G, mid-tier Android | < 2.5s | The twenty-second claim starts when the page opens |
| Report composer JS, initial | < 120 KB gzipped | Leaflet loads only when manual pin placement is needed |
| Photo payload after client compression | ~300 KB | Venue and field network |
| Map marker query | < 300ms for 500 markers | |
| Dashboard tile queries | < 500ms over 90 days | |
| Minimum touch target | 48 × 48 px | One-handed outdoor use |
| Minimum contrast | WCAG AA, all tokens on both surfaces | Direct sunlight |
| Smallest supported viewport | 360 px | |

Non-negotiable: every interactive element keyboard-reachable with a visible focus ring; every form input with a real associated label; status communicated by colour plus shape plus word; `prefers-reduced-motion` honoured; every list with a specified empty state.

---

## 9. Copy rules

Plain verbs, sentence case, no filler. Buttons name what happens: "Submit for citizen verification," not "Submit."

Never say "resolved" where the system means "pending verification." The vocabulary difference between those two words is the project's central argument and the interface must not blur it.

Errors say what happened and what to do: "GPS accuracy is 340 m. Place the pin on the map instead." They do not apologise and they are never vague.

Empty states are invitations, not apologies. "Nothing needs review" is a good outcome and reads as one.

Never expose internal vocabulary to citizens. A citizen sees "you are the 4th person to report this," never "clustered" or "merged into issue 432." Staff and supervisors do see the technical vocabulary, because for them it is the domain language.

---

## 10. Not building

Recorded here so that a reviewer sees a boundary rather than a gap: no admin configuration screens, no dark mode, no email notification preferences, no password reset, no reverse-geocoded addresses, no heatmap or choropleth layer, no offline queue with background sync, no multilingual interface. Reasoning is in report §5.2 and §23.
