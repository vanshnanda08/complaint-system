# CivicTrack — Complete Technical Blueprint (Java / Spring Boot Edition)

*Municipal issue reporting with incremental geo-clustering, SLA escalation, and public accountability.*

---
> **SUPERSEDED IN SIX PLACES.** This document is the implementation
> reference for schema, queries, and code structure. Its design is
> corrected in six places by `civictrack-project-report-v2.md` §12:
> cluster extent cap, category-configurable low-confidence band,
> post-lock distance re-verification, scheduled priority recomputation,
> explicit capped escalation ladder, and unverified-closure reporting.
> The stack is also revised: §9 of the report supersedes §8 here.
> Where this file and the report disagree, the report wins.

## 0. What changes when you move to Java

| Concern | Original (Node) | Java replacement | Notes |
|---|---|---|---|
| Runtime | Node 20 | **Java 21 (LTS)** | Use 21, not 17 — virtual threads, pattern matching for switch |
| Framework | Express | **Spring Boot 3.5.x** | 4.0/4.1 exist but 3.5 has the widest tutorial/library coverage and extended support; pick 3.5.x for a project you have to finish |
| ORM | Prisma / Sequelize | **Spring Data JPA + Hibernate 6 + `hibernate-spatial`** | JTS `Point` maps straight to `geometry(Point,4326)` |
| Migrations | Prisma migrate | **Flyway** | Plain SQL files — you need raw PostGIS DDL anyway |
| Scheduler | node-cron | **`@Scheduled` + ShedLock** | ShedLock gives you the distributed lock that makes the job idempotent |
| Auth | Passport + jsonwebtoken | **Spring Security + jjwt 0.12** | Stateless, `@PreAuthorize` for method-level RBAC |
| Uploads | multer | **`MultipartFile` + Cloudinary Java SDK** | Same Cloudinary account, different client |
| Validation | zod / joi | **Jakarta Bean Validation** (`@Valid`, `@NotNull`, custom constraints) | |
| Tests | Jest + supertest | **JUnit 5 + MockMvc + Testcontainers** | Testcontainers runs a real `postgis/postgis` image — non-negotiable, you cannot unit-test spatial SQL with H2 |
| Realtime | socket.io | **`SseEmitter`** (Server-Sent Events) | One-way server→client is all the demo needs; far less code than WebSocket |
| Deploy | Render Node service | **Render Docker web service** | Multistage Dockerfile, `-XX:MaxRAMPercentage=70` for the 512 MB tier |

Everything else — Next.js, PostgreSQL + PostGIS, Leaflet, Cloudinary, Vercel — is unchanged.

**Frontend/backend contract is unchanged too.** The API is REST/JSON either way, so nothing about the Next.js side needs rethinking.

---

## 1. The domain model — the decision to lead with

> "The biggest design decision was separating **reports** from **issues** in the schema."

Make this concrete, because it's the thing that makes everything else possible:

**A Report is an immutable citizen observation.**
It has its own raw GPS point, its own accuracy reading, its own photo, its own reporter and timestamp. It is never edited and never deleted. It is *evidence*.

**An Issue is a mutable municipal work item.**
It has a *derived* centroid, a status, an assignee, an SLA clock, a resolution. It is *work*.

The relationship is many reports → one issue, and a report can be **moved between issues** without losing its identity.

Four things fall out of this separation, and none of them are possible without it:

1. **Volume becomes signal.** `issue.report_count` is a priority input. In a flat "one complaint = one row" schema, twenty reports are twenty rows of noise and the count is meaningless.
2. **Lifecycle separates from evidence.** Closing an issue doesn't destroy the twenty citizen observations behind it. The audit trail survives.
3. **Split and merge are non-destructive.** A moderator reassigns `report.issue_id` and recomputes both centroids. Nothing is lost, and the operation is reversible.
4. **Verification has an addressable audience.** "Who do we ask whether this is actually fixed?" has an answer: everyone in `reports WHERE issue_id = X`.

### Entity list

| Entity | Role |
|---|---|
| `users` | citizens, staff, supervisors, admins |
| `departments` | self-referencing tree (crew → dept → zone), drives the escalation ladder |
| `wards` | administrative polygons; clustering never crosses a ward boundary |
| `categories` | pothole, garbage, etc. — carries `merge_radius_m` and `default_sla_hours` |
| `reports` | immutable citizen observations |
| `issues` | mutable work items, derived centroid, SLA clock |
| `issue_status_history` | every transition, who and when |
| `escalation_events` | one row per (issue, level) — this row *is* the idempotency guarantee |
| `verifications` | citizen fixed/not-fixed verdicts |
| `notifications` | outbound queue |
| `shedlock` | ShedLock's own table |
1. What the 2.0 multiplies, and what a defensible sweep needs
What it multiplies. The cap is max_extent_multiplier × merge_radius_m, so for POTHOLE (25 m) it is 50 m. It bounds issues.max_member_dist_m — the geodesic distance from the centroid to the furthest member report. That is a cluster radius, not a diameter, so a POTHOLE cluster may legitimately span 100 m end to end while satisfying a 50 m cap. Worth being precise about, because "50 m cap" and "50 m wide" differ by a factor of two and the sweep plot axis has to say which.

At the boundary. Merging moves the centroid, which changes every existing member's distance, so an exact post-merge extent is an O(n) rescan — the thing the running sums exist to avoid. Instead I compute an O(1) upper bound. If the centroid shifts by δ, then by the triangle inequality no existing member can be further than old_max + δ from the new centroid, and the arriving report sits at d_new. So:


projected_extent ≤ max(old_max_member_dist_m + δ, d_new)
If that exceeds the cap the merge is refused: a new issue is created, cluster_decision = SPLIT_EXTENT_CAPPED, needs_review = true. The bound is conservative — it can overestimate, so the cap can refuse a merge that an exact recomputation would have allowed. That errs toward splitting and flagging for a human, which is the safe direction, but it means a measured "cap bound here" rate is a slight overcount and you should say so rather than let a reviewer find it.

What a sweep needs. Input: a labelled corpus whose ground truth contains linear defects specifically — chaining only occurs when reports repeatedly arrive within R_eff of a moving centroid — with point defects as a control arm, generated across a range of report densities and inter-report spacings. Because single-pass assignment is order-dependent, each corpus must be run under several arrival shuffles and the spread reported, not just a mean.

Measure, per multiplier in roughly 1.25 → 4.0 plus a disabled (∞) arm: clustering precision/recall/F1 against ground truth; the distribution of final max_member_dist_m; the rate of SPLIT_EXTENT_CAPPED; and fragmentation (issues per ground-truth defect).

The result that justifies moving off1. What the 2.0 multiplies, and what a defensible sweep needs
What it multiplies. The cap is max_extent_multiplier × merge_radius_m, so for POTHOLE (25 m) it is 50 m. It bounds issues.max_member_dist_m — the geodesic distance from the centroid to the furthest member report. That is a cluster radius, not a diameter, so a POTHOLE cluster may legitimately span 100 m end to end while satisfying a 50 m cap. Worth being precise about, because "50 m cap" and "50 m wide" differ by a factor of two and the sweep plot axis has to say which.

At the boundary. Merging moves the centroid, which changes every existing member's distance, so an exact post-merge extent is an O(n) rescan — the thing the running sums exist to avoid. Instead I compute an O(1) upper bound. If the centroid shifts by δ, then by the triangle inequality no existing member can be further than old_max + δ from the new centroid, and the arriving report sits at d_new. So:


projected_extent ≤ max(old_max_member_dist_m + δ, d_new)
If that exceeds the cap the merge is refused: a new issue is created, cluster_decision = SPLIT_EXTENT_CAPPED, needs_review = true. The bound is conservative — it can overestimate, so the cap can refuse a merge that an exact recomputation would have allowed. That errs toward splitting and flagging for a human, which is the safe direction, but it means a measured "cap bound here" rate is a slight overcount and you should say so rather than let a reviewer find it.

What a sweep needs. Input: a labelled corpus whose ground truth contains linear defects specifically — chaining only occurs when reports repeatedly arrive within R_eff of a moving centroid — with point defects as a control arm, generated across a range of report densities and inter-report spacings. Because single-pass assignment is order-dependent, each corpus must be run under several arrival shuffles and the spread reported, not just a mean.

Measure, per multiplier in roughly 1.25 → 4.0 plus a disabled (∞) arm: clustering precision/recall/F1 against ground truth; the distribution of final max_member_dist_m; the rate of SPLIT_EXTENT_CAPPED; and fragmentation (issues per ground-truth defect).

The result that justifies moving off 2.0: F1 peaking materially away from 2.0. The result that justifies deleting the feature is the ∞ arm showing no cluster ever exceeding 2 × R_cat at realistic densities — the cap never binds, so it is machinery defending against a hypothetical. A third outcome is live: the cap helping on linear corpora while fragmenting point corpora, which argues for per-category values, and the schema already stores it per category.

One trap to avoid: do not measure "merges refused" as if lower extent were the goal. Refusing every merge drives extent to zero and F1 to the floor. F1 against ground truth is the only metric that can move this number.

2. Fixture check
Confirmed, and it matters more than the ward grid alone:

Point	→ ward boundary	Verdict
(30.900965, 75.857277) demo	107 m	Unusable as a fixture
(30.9300, 75.8200)	3215 m	Ward-interior
No phase-2 test uses the demo coordinate. I did not have to change an existing test — phase 1's WardContainmentIT already used (30.9300, 75.8200). GeoFactoryTest does reference the demo coordinate, but only to assert x/y ordering with no ward lookup, so it is unaffected.

I did tighten my intended fixture while checking. My first choice, (30.9310, 75.8210), is ward-interior but lands exactly on a ST_SnapToGrid(0.002) midpoint — the worst spot for advisory-lock cell determinism. (30.9300, 75.8200) is a cell centre, ~95 m from the nearest cell edge in longitude and ~111 m in latitude. That is the phase-2 canonical fixture.

 2.0: F1 peaking materially away from 2.0. The result that justifies deleting the feature is the ∞ arm showing no cluster ever exceeding 2 × R_cat at realistic densities — the cap never binds, so it is machinery defending against a hypothetical. A third outcome is live: the cap helping on linear corpora while fragmenting point corpora, which argues for per-category values, and the schema already stores it per category.

One trap to avoid: do not measure "merges refused" as if lower extent were the goal. Refusing every merge drives extent to zero and F1 to the floor. F1 against ground truth is the only metric that can move this number.

2. Fixture check
Confirmed, and it matters more than the ward grid alone:

Point	→ ward boundary	Verdict
(30.900965, 75.857277) demo	107 m	Unusable as a fixture
(30.9300, 75.8200)	3215 m	Ward-interior
No phase-2 test uses the demo coordinate. I did not have to change an existing test — phase 1's WardContainmentIT already used (30.9300, 75.8200). GeoFactoryTest does reference the demo coordinate, but only to assert x/y ordering with no ward lookup, so it is unaffected.

I did tighten my intended fixture while checking. My first choice, (30.9310, 75.8210), is ward-interior but lands exactly on a ST_SnapToGrid(0.002) midpoint — the worst spot for advisory-lock cell determinism. (30.9300, 75.8200) is a cell centre, ~95 m from the nearest cell edge in longitude and ~111 m in latitude. That is the phase-2 canonical fixture.

---

## 2. Database schema

### `V1__baseline.sql`

```sql
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()

-- ---------- reference data ----------

CREATE TABLE departments (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                 VARCHAR(40)  NOT NULL UNIQUE,
    name                 VARCHAR(160) NOT NULL,
    parent_department_id UUID REFERENCES departments(id),
    head_user_id         UUID,                       -- FK added after users
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE wards (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ward_number INT          NOT NULL UNIQUE,
    name        VARCHAR(160) NOT NULL,
    boundary    geometry(MultiPolygon, 4326) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_wards_boundary ON wards USING GIST (boundary);

CREATE TABLE categories (
    code             VARCHAR(40) PRIMARY KEY,
    display_name     VARCHAR(120) NOT NULL,
    merge_radius_m   INT  NOT NULL,        -- category-specific clustering radius
    default_sla_hours INT NOT NULL,
    severity_weight  INT  NOT NULL DEFAULT 10,   -- priority scoring input
    is_mobile_target BOOLEAN NOT NULL DEFAULT FALSE, -- stray animals etc.
    department_id    UUID REFERENCES departments(id),
    active           BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(180) UNIQUE,
    phone         VARCHAR(20)  UNIQUE,
    password_hash VARCHAR(120),
    full_name     VARCHAR(160) NOT NULL,
    role          VARCHAR(20)  NOT NULL
                  CHECK (role IN ('CITIZEN','STAFF','SUPERVISOR','ADMIN')),
    department_id UUID REFERENCES departments(id),
    ward_id       UUID REFERENCES wards(id),
    reputation    INT NOT NULL DEFAULT 100,
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (email IS NOT NULL OR phone IS NOT NULL)
);

ALTER TABLE departments
    ADD CONSTRAINT fk_dept_head FOREIGN KEY (head_user_id) REFERENCES users(id);

-- ---------- core ----------

CREATE TABLE issues (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    public_ref          VARCHAR(20)  NOT NULL UNIQUE,     -- CT-2026-000431
    category_code       VARCHAR(40)  NOT NULL REFERENCES categories(code),
    ward_id             UUID         NOT NULL REFERENCES wards(id),
    department_id       UUID         REFERENCES departments(id),

    status              VARCHAR(24)  NOT NULL DEFAULT 'NEW',
    priority            VARCHAR(12)  NOT NULL DEFAULT 'MEDIUM',
    priority_score      NUMERIC(8,2) NOT NULL DEFAULT 0,

    -- derived cluster geometry
    centroid            geometry(Point, 4326) NOT NULL,
    sum_w               DOUBLE PRECISION NOT NULL,   -- Σ wᵢ            (1/m²)
    sum_wx              DOUBLE PRECISION NOT NULL,   -- Σ wᵢ·lonᵢ
    sum_wy              DOUBLE PRECISION NOT NULL,   -- Σ wᵢ·latᵢ
    report_count        INT NOT NULL DEFAULT 0,
    distinct_reporters  INT NOT NULL DEFAULT 0,
    cluster_confidence  VARCHAR(10) NOT NULL DEFAULT 'HIGH'
                        CHECK (cluster_confidence IN ('HIGH','LOW')),
    needs_review        BOOLEAN NOT NULL DEFAULT FALSE,

    -- SLA clock
    first_reported_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_reported_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    acknowledged_at     TIMESTAMPTZ,
    due_at              TIMESTAMPTZ NOT NULL,
    clock_paused_at     TIMESTAMPTZ,
    paused_seconds      BIGINT NOT NULL DEFAULT 0,
    escalation_level    INT NOT NULL DEFAULT 0,
    last_escalated_at   TIMESTAMPTZ,

    assigned_to         UUID REFERENCES users(id),
    assigned_at         TIMESTAMPTZ,

    resolution_note     TEXT,
    resolution_photo_url TEXT,
    resolution_photo_hash VARCHAR(64),   -- dHash, anti-fraud
    resolved_by         UUID REFERENCES users(id),
    resolved_at         TIMESTAMPTZ,
    closed_at           TIMESTAMPTZ,
    reopen_count        INT NOT NULL DEFAULT 0,

    rejected_reason     TEXT,
    version             BIGINT NOT NULL DEFAULT 0,   -- JPA @Version
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_status CHECK (status IN
      ('NEW','ACKNOWLEDGED','ASSIGNED','IN_PROGRESS','PENDING_VERIFICATION',
       'RESOLVED','REOPENED','CLOSED','REJECTED')),
    CONSTRAINT chk_priority CHECK (priority IN ('LOW','MEDIUM','HIGH','CRITICAL'))
);

-- the workhorse index: candidate lookup during clustering
CREATE INDEX idx_issues_centroid_geog ON issues USING GIST ((centroid::geography));
CREATE INDEX idx_issues_open_cat      ON issues (category_code, ward_id, status)
    WHERE status NOT IN ('CLOSED','REJECTED');
CREATE INDEX idx_issues_due           ON issues (due_at)
    WHERE status NOT IN ('CLOSED','REJECTED','RESOLVED');
CREATE INDEX idx_issues_ward_status   ON issues (ward_id, status);

CREATE TABLE reports (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    issue_id         UUID NOT NULL REFERENCES issues(id),
    reporter_id      UUID REFERENCES users(id),     -- null = anonymous
    device_id        VARCHAR(64),                   -- anon dedupe / rate limit
    category_code    VARCHAR(40) NOT NULL REFERENCES categories(code),

    location         geometry(Point, 4326) NOT NULL,
    gps_accuracy_m   DOUBLE PRECISION NOT NULL,
    address_text     VARCHAR(300),
    landmark         VARCHAR(200),
    description      TEXT,
    photo_url        TEXT NOT NULL,
    photo_hash       VARCHAR(64),

    -- clustering audit: why did this report land where it landed?
    cluster_decision VARCHAR(20) NOT NULL
                     CHECK (cluster_decision IN ('NEW_ISSUE','MERGED','MERGED_LOW_CONF','MANUAL')),
    cluster_distance_m DOUBLE PRECISION,
    effective_radius_m DOUBLE PRECISION,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reports_issue    ON reports (issue_id);
CREATE INDEX idx_reports_location ON reports USING GIST ((location::geography));
CREATE INDEX idx_reports_reporter ON reports (reporter_id, created_at DESC);

-- ---------- audit / workflow ----------

CREATE TABLE issue_status_history (
    id          BIGSERIAL PRIMARY KEY,
    issue_id    UUID NOT NULL REFERENCES issues(id),
    from_status VARCHAR(24),
    to_status   VARCHAR(24) NOT NULL,
    actor_id    UUID REFERENCES users(id),   -- null = SYSTEM
    actor_role  VARCHAR(20) NOT NULL,
    note        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_history_issue ON issue_status_history (issue_id, created_at);

CREATE TABLE escalation_events (
    id            BIGSERIAL PRIMARY KEY,
    issue_id      UUID NOT NULL REFERENCES issues(id),
    level         INT  NOT NULL,
    from_user_id  UUID REFERENCES users(id),
    to_user_id    UUID REFERENCES users(id),
    reason        VARCHAR(60) NOT NULL,        -- SLA_BREACH | REOPEN | MANUAL
    breached_by_seconds BIGINT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_escalation UNIQUE (issue_id, level)   -- ← idempotency guarantee
);

CREATE TABLE verifications (
    id          BIGSERIAL PRIMARY KEY,
    issue_id    UUID NOT NULL REFERENCES issues(id),
    citizen_id  UUID NOT NULL REFERENCES users(id),
    verdict     VARCHAR(12) NOT NULL CHECK (verdict IN ('FIXED','NOT_FIXED')),
    comment     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_verification UNIQUE (issue_id, citizen_id)  -- one vote each
);

CREATE TABLE notifications (
    id          BIGSERIAL PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id),
    issue_id    UUID REFERENCES issues(id),
    type        VARCHAR(40) NOT NULL,
    title       VARCHAR(200) NOT NULL,
    body        TEXT,
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notif_user ON notifications (user_id, read_at, created_at DESC);

CREATE TABLE shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
```

### Why `geometry` + `::geography` cast rather than a `geography` column

`geography` gives you metres for free but is clumsier through Hibernate and slower for the bounding-box prefilter. Storing `geometry(Point,4326)` and casting at query time gives you clean JPA mapping *and* metre-accurate distances — provided you build the GiST index **on the cast expression**, which is what `USING GIST ((centroid::geography))` above does. Without that functional index the planner falls back to a sequential scan and your clustering query degrades from ~1 ms to ~400 ms at 50k issues. Worth a sentence in the report.

### Seed radii and SLAs

| Category | `merge_radius_m` | Rationale | SLA (hrs) |
|---|---|---|---|
| `POTHOLE` | 25 | Point defect, sharp spatial extent | 72 |
| `STREETLIGHT` | 30 | Anchored to a physical pole | 96 |
| `GARBAGE_DUMP` | 50 | Spreads; people report from the edges | 48 |
| `ILLEGAL_DUMPING` | 50 | Same, plus intermittent | 48 |
| `WATER_LEAK` | 40 | Water travels downhill from the source | 24 |
| `DRAINAGE_BLOCK` | 40 | Backs up along the line | 24 |
| `ROAD_DAMAGE` | 60 | Linear defect, not a point | 120 |
| `SIGNAGE` | 20 | Single object | 168 |
| `STRAY_ANIMAL` | 120 | Target is mobile — widest radius, shortest reopen window | 24 |
| `OPEN_MANHOLE` | 20 | Safety-critical | 6 |

Put these in the `categories` table, not in code. When an examiner asks "how did you choose 25 m?", the answer is that it's *configuration derived from the physical extent of the defect*, tuned against seed data — not a magic number compiled into a service.

---

## 3. The clustering engine

This is the technical core. Everything else in the project is competent CRUD; this is the part that makes it a *systems* project.

### 3.1 The problem statement

Reports arrive as an **unbounded live stream**, one at a time, each carrying its own positional uncertainty. For every arriving report you must decide, in the request cycle (target < 150 ms), whether it belongs to an existing open issue or starts a new one — and that decision must be stable under concurrent arrivals.

### 3.2 Why not DBSCAN (prepare this answer, you will be asked)

DBSCAN is **batch density clustering over a static dataset**. It partitions a fixed point set in one pass, and adding a single point can restructure clusters globally — a new point can bridge two previously separate clusters and merge them. That has three consequences that disqualify it here:

1. **You'd have to re-run it on every insert.** O(n log n) per report over a growing city-wide dataset, inside an HTTP request.
2. **Cluster identity is unstable.** DBSCAN doesn't guarantee that cluster #7 in run *k* is cluster #7 in run *k+1*. But `issue.id` is a public ticket number that a citizen has in an SMS and a staff member has in a work queue. It cannot be reassigned by a re-clustering pass.
3. **It has no notion of state.** A resolved pothole and an open pothole are the same point to DBSCAN. Your merge decision depends on issue status, ward, category, and age — none of which are spatial.

What you actually need is **incremental online clustering** with a fixed assignment rule: leader/threshold clustering, single-pass, assignment is final unless a human intervenes. Distance is computed against a maintained cluster representative (the weighted centroid), not against every member.

The honest caveat to volunteer before they raise it: single-pass assignment is **order-dependent** and cannot merge two clusters that later turn out to be one. That's exactly why the manual split/merge tool and the `needs_review` flag exist — they are the designed escape hatch for the algorithm's known limitation, not an afterthought.

### 3.3 Accuracy-weighted centroid

Each report carries `gps_accuracy_m` from the browser Geolocation API. A report accurate to 5 m should pull the centroid much harder than one accurate to 60 m. Weight by inverse variance:

$$w_i = \frac{1}{\max(a_i,\ a_{\min})^2}, \qquad a_{\min} = 3\ \text{m}$$

Centroid is the weighted mean:

$$\bar{\lambda} = \frac{\sum_i w_i \lambda_i}{\sum_i w_i}, \qquad \bar{\varphi} = \frac{\sum_i w_i \varphi_i}{\sum_i w_i}$$

Maintain `sum_w`, `sum_wx`, `sum_wy` as running columns on the issue row, so each update is **O(1)** — no rescan of member reports. This is the single most important implementation detail in the whole algorithm.

Because `wᵢ` has units of 1/m², `Σwᵢ` does too, and the standard error of the centroid falls out for free:

$$\sigma_{\text{issue}} = \frac{1}{\sqrt{\sum_i w_i}}$$

Which you then use to size the merge radius. Two reports at 10 m accuracy give σ ≈ 7 m; twenty give σ ≈ 2 m. **A well-established cluster gets a tighter effective radius than a brand new one**, automatically, with no tuning.

*Caveat for the report:* the weighted mean is computed in planar lon/lat. At Ludhiana's latitude one degree of longitude is ~96 km against ~111 km for latitude, so the axes are anisotropic — but over cluster extents under 150 m the induced centroid error is sub-millimetre. Distances are always measured with `ST_Distance(...::geography)`, which is true geodesic metres, so the anisotropy never touches the *decision*, only the representative point.

### 3.4 Adaptive merge radius

$$R_{\text{eff}} = R_{\text{cat}} + \tfrac{1}{2}\min(a_{\text{new}},\ 60) + \tfrac{1}{2}\,\sigma_{\text{issue}}$$

Then three decision bands on the geodesic distance *d* from the new report to the candidate centroid:

| Band | Decision | `cluster_decision` |
|---|---|---|
| d ≤ R_eff | Merge | `MERGED` |
| R_eff < d ≤ 1.5 · R_eff | Merge **and** flag `needs_review = true`, `cluster_confidence = LOW` | `MERGED_LOW_CONF` |
| d > 1.5 · R_eff | Create new issue | `NEW_ISSUE` |

The middle band is the interesting one. It's the explicit admission that a hard threshold produces arbitrary decisions near the boundary, so instead of guessing, the system merges (the cheaper error — a wrong merge is one click to split, a wrong split leaves two tickets nobody notices) and surfaces it in a moderator review queue.

**Hard preconditions** — checked before any distance maths:

- same `category_code` (a pothole and a garbage pile at the same coordinate are two problems)
- same `ward_id` (never cluster across an administrative boundary — different department, different SLA owner)
- candidate status is open, **or** `RESOLVED` within the reopen window
- reject the report outright if `gps_accuracy_m > 150` → ask the user to place the pin manually on the map

### 3.5 Reopen-on-recurrence

If the nearest candidate is `RESOLVED` and `resolved_at > now() - reopen_window` (14 days; 3 days for `STRAY_ANIMAL`), attach the report and transition the issue to `REOPENED` with `escalation_level = max(1, level + 1)` and a halved SLA.

This is the accountability feature that's worth calling out in the pitch: **a department cannot close a ticket, have the problem recur a week later, and get a fresh SLA clock**. The reopen count is on the public dashboard.

### 3.6 Concurrency — the "three phones at once" problem

Your demo is literally three people submitting at the same moment. Without a lock, three transactions each find no candidate and each create an issue. You'd demo the bug live.

Two layers:

**Layer 1 — advisory lock on a spatial cell.** Serialise concurrent inserts that could possibly interact:

```sql
SELECT pg_advisory_xact_lock(
  hashtext(:categoryCode || ':' ||
           ST_AsText(ST_SnapToGrid(ST_SetSRID(ST_MakePoint(:lng,:lat),4326), 0.002)))
);
```

`ST_SnapToGrid` at 0.002° ≈ a 200 m × 220 m cell. All simultaneous reports in the same cell and category serialise; reports elsewhere in the city proceed in parallel. The lock is transaction-scoped and released automatically.

*Known limitation, state it before you're asked:* two reports straddling a cell boundary can still race. The probability is low (the cell is 8× the largest merge radius) and the failure mode is benign — a duplicate issue that the review queue catches. Locking on the 3×3 cell neighbourhood removes it entirely at the cost of throughput; that trade is documented and deliberate.

**Layer 2 — `FOR UPDATE` on the candidate row.** Once a candidate issue is chosen, it's row-locked before the centroid update, so two reports merging into the *same* issue can't interleave their `sum_w` updates.

### 3.7 The candidate query

```sql
SELECT i.id,
       ST_Distance(i.centroid::geography,
                   ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distance_m,
       i.sum_w,
       i.status,
       i.resolved_at
FROM issues i
WHERE i.category_code = :categoryCode
  AND i.ward_id       = :wardId
  AND (
        i.status IN ('NEW','ACKNOWLEDGED','ASSIGNED','IN_PROGRESS',
                     'PENDING_VERIFICATION','REOPENED')
     OR (i.status = 'RESOLVED'
         AND i.resolved_at > now() - make_interval(days => :reopenDays))
      )
  AND ST_DWithin(i.centroid::geography,
                 ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                 :searchRadiusM)
ORDER BY distance_m ASC
LIMIT 5
FOR UPDATE OF i;
```

`ST_DWithin` is the index-using predicate (it applies a bounding-box prefilter through the GiST index); `ST_Distance` in the `ORDER BY` only runs on the survivors. `:searchRadiusM` is `1.5 × R_cat + 120` — generous enough that the low-confidence band is never truncated.

### 3.8 The service, in Java

```java
@Service
@RequiredArgsConstructor
public class ClusteringService {

    private final IssueRepository issueRepo;
    private final ReportRepository reportRepo;
    private final CategoryRepository categoryRepo;
    private final WardRepository wardRepo;
    private final PriorityCalculator priorityCalculator;
    private final SlaService slaService;
    private final ApplicationEventPublisher events;

    private static final double MIN_ACCURACY_M    = 3.0;
    private static final double MAX_ACCURACY_M    = 150.0;
    private static final double LOW_CONF_FACTOR   = 1.5;
    private static final GeometryFactory GF =
            new GeometryFactory(new PrecisionModel(), 4326);

    @Transactional
    public ClusterResult ingest(IngestReportCommand cmd) {

        if (cmd.accuracyM() > MAX_ACCURACY_M) {
            throw new LowAccuracyException(cmd.accuracyM());
        }

        Category category = categoryRepo.findActive(cmd.categoryCode())
                .orElseThrow(() -> new UnknownCategoryException(cmd.categoryCode()));

        Ward ward = wardRepo.findContaining(cmd.lng(), cmd.lat())
                .orElseThrow(OutsideServiceAreaException::new);

        // 1. serialise everyone reporting the same thing in the same 200m cell
        issueRepo.acquireCellLock(category.getCode(), cmd.lng(), cmd.lat());

        double accuracy = Math.max(cmd.accuracyM(), MIN_ACCURACY_M);
        double weight   = 1.0 / (accuracy * accuracy);
        double searchRadius = 1.5 * category.getMergeRadiusM() + 120;

        // 2. nearest open candidates, row-locked
        List<CandidateRow> candidates = issueRepo.findMergeCandidates(
                category.getCode(), ward.getId(), cmd.lng(), cmd.lat(),
                searchRadius, category.getReopenWindowDays());

        Issue target;
        ClusterDecision decision;
        Double distance = null, effectiveRadius = null;

        if (candidates.isEmpty()) {
            target   = createIssue(cmd, category, ward, weight);
            decision = ClusterDecision.NEW_ISSUE;
        } else {
            CandidateRow best = candidates.get(0);
            double sigmaIssue = 1.0 / Math.sqrt(best.getSumW());
            effectiveRadius = category.getMergeRadiusM()
                            + 0.5 * Math.min(cmd.accuracyM(), 60.0)
                            + 0.5 * sigmaIssue;
            distance = best.getDistanceM();

            if (distance <= effectiveRadius) {
                decision = ClusterDecision.MERGED;
            } else if (distance <= LOW_CONF_FACTOR * effectiveRadius) {
                decision = ClusterDecision.MERGED_LOW_CONF;
            } else {
                decision = ClusterDecision.NEW_ISSUE;
            }

            if (decision == ClusterDecision.NEW_ISSUE) {
                target = createIssue(cmd, category, ward, weight);
            } else {
                target = issueRepo.findById(best.getId()).orElseThrow();
                mergeInto(target, cmd, weight, decision);
            }
        }

        Report report = reportRepo.save(Report.builder()
                .issue(target)
                .reporterId(cmd.reporterId())
                .deviceId(cmd.deviceId())
                .categoryCode(category.getCode())
                .location(point(cmd.lng(), cmd.lat()))
                .gpsAccuracyM(cmd.accuracyM())
                .description(cmd.description())
                .addressText(cmd.addressText())
                .photoUrl(cmd.photoUrl())
                .photoHash(cmd.photoHash())
                .clusterDecision(decision)
                .clusterDistanceM(distance)
                .effectiveRadiusM(effectiveRadius)
                .build());

        target.setPriorityScore(priorityCalculator.score(target));
        target.setPriority(priorityCalculator.band(target.getPriorityScore()));
        slaService.tightenIfNeeded(target);   // priority can shorten a deadline, never extend it

        events.publishEvent(new ReportIngestedEvent(target.getId(),
                target.getReportCount(), decision));

        return new ClusterResult(target.getId(), target.getPublicRef(),
                decision, target.getReportCount(), distance);
    }

    private void mergeInto(Issue issue, IngestReportCommand cmd,
                           double w, ClusterDecision decision) {
        // O(1) incremental weighted centroid — no rescan of member reports
        double newSumW  = issue.getSumW()  + w;
        double newSumWx = issue.getSumWx() + w * cmd.lng();
        double newSumWy = issue.getSumWy() + w * cmd.lat();

        issue.setSumW(newSumW);
        issue.setSumWx(newSumWx);
        issue.setSumWy(newSumWy);
        issue.setCentroid(point(newSumWx / newSumW, newSumWy / newSumW));
        issue.setReportCount(issue.getReportCount() + 1);
        issue.setLastReportedAt(Instant.now());

        if (decision == ClusterDecision.MERGED_LOW_CONF) {
            issue.setClusterConfidence(ClusterConfidence.LOW);
            issue.setNeedsReview(true);
        }
        if (issue.getStatus() == IssueStatus.RESOLVED) {
            statusService.transition(issue, IssueStatus.REOPENED, SYSTEM,
                    "Recurrence reported within reopen window");
        }
    }

    private Point point(double lng, double lat) {
        return GF.createPoint(new Coordinate(lng, lat));   // JTS is (x=lng, y=lat)
    }
}
```

**JTS coordinate order is (x, y) = (longitude, latitude).** Every geo bug in this project will be a swapped lat/lng. Write it once in a factory method, as above, and never construct a `Point` anywhere else.

### 3.9 Repository

```java
public interface IssueRepository extends JpaRepository<Issue, UUID> {

    @Query(value = """
        SELECT pg_advisory_xact_lock(
          hashtext(:cat || ':' ||
            ST_AsText(ST_SnapToGrid(
              ST_SetSRID(ST_MakePoint(:lng, :lat), 4326), 0.002))))
        """, nativeQuery = true)
    void acquireCellLock(@Param("cat") String categoryCode,
                         @Param("lng") double lng,
                         @Param("lat") double lat);

    @Query(value = """
        SELECT i.id            AS id,
               ST_Distance(i.centroid::geography,
                    ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography) AS distanceM,
               i.sum_w         AS sumW,
               i.status        AS status
        FROM issues i
        WHERE i.category_code = :cat
          AND i.ward_id = :wardId
          AND (i.status IN ('NEW','ACKNOWLEDGED','ASSIGNED','IN_PROGRESS',
                            'PENDING_VERIFICATION','REOPENED')
               OR (i.status = 'RESOLVED'
                   AND i.resolved_at > now() - make_interval(days => :reopenDays)))
          AND ST_DWithin(i.centroid::geography,
                         ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography, :radius)
        ORDER BY distanceM ASC
        LIMIT 5
        FOR UPDATE OF i
        """, nativeQuery = true)
    List<CandidateRow> findMergeCandidates(@Param("cat") String cat,
                                           @Param("wardId") UUID wardId,
                                           @Param("lng") double lng,
                                           @Param("lat") double lat,
                                           @Param("radius") double radius,
                                           @Param("reopenDays") int reopenDays);

    interface CandidateRow {
        UUID getId();
        Double getDistanceM();
        Double getSumW();
        String getStatus();
    }
}
```

### 3.10 Split and merge (the moderator tool)

**Split** — supervisor selects a subset of reports from an issue:

```java
@Transactional
@PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN')")
public Issue split(UUID sourceIssueId, Set<UUID> reportIds, String reason) {
    Issue source = issueRepo.findByIdForUpdate(sourceIssueId).orElseThrow();
    if (reportIds.size() >= source.getReportCount()) {
        throw new IllegalSplitException("Cannot move every report out of an issue");
    }
    Issue target = Issue.newFrom(source);           // same category/ward, fresh ref + clock
    reportRepo.reassign(reportIds, target.getId(), ClusterDecision.MANUAL);

    recomputeAggregates(source);                    // full recompute, both sides
    recomputeAggregates(target);
    source.setNeedsReview(false);
    auditService.recordSplit(source, target, reportIds, reason);
    return target;
}
```

`recomputeAggregates` is the only place that rescans — it's an O(n) aggregate over one issue's reports, run on a human-triggered action, not in the hot path:

```sql
UPDATE issues i SET
    sum_w   = agg.sw,
    sum_wx  = agg.swx,
    sum_wy  = agg.swy,
    centroid = ST_SetSRID(ST_MakePoint(agg.swx / agg.sw, agg.swy / agg.sw), 4326),
    report_count = agg.n,
    distinct_reporters = agg.dr
FROM (
    SELECT SUM(1.0 / power(GREATEST(gps_accuracy_m, 3), 2))                        AS sw,
           SUM(1.0 / power(GREATEST(gps_accuracy_m, 3), 2) * ST_X(location))       AS swx,
           SUM(1.0 / power(GREATEST(gps_accuracy_m, 3), 2) * ST_Y(location))       AS swy,
           COUNT(*)                                                                AS n,
           COUNT(DISTINCT COALESCE(reporter_id::text, device_id))                  AS dr
    FROM reports WHERE issue_id = :issueId
) agg
WHERE i.id = :issueId;
```

**Merge** is the inverse: move all reports from B into A, recompute A, mark B `REJECTED` with `rejected_reason = 'Merged into ' || A.public_ref`, and repoint anyone watching B.

### 3.11 Priority scoring

```
score = severity_weight(category)
      + 12 · log₂(1 + distinct_reporters)
      + 0.15 · age_hours
      + 20 · escalation_level
      + 15 · reopen_count
```

Bands: `< 25 LOW`, `25–49 MEDIUM`, `50–79 HIGH`, `≥ 80 CRITICAL`.

`log₂` rather than linear count so that going 1 → 5 reporters matters a lot and 40 → 45 matters little — one street can't monopolise the queue by spamming. Counting **distinct reporters**, not raw reports, is what stops a single person refreshing the form twenty times.

### 3.12 Edge cases to have answers for

| Case | Handling |
|---|---|
| GPS accuracy 500 m (indoors, no signal) | Rejected at ingest; user drags a pin on the map, accuracy recorded as 10 m with `manual_pin = true` |
| Report exactly on a ward boundary | `ST_Contains` picks one ward deterministically; supervisor can reassign ward, which triggers a recompute |
| Same person reports twice in 30 s | Rate limit: one report per device per category per 5 minutes within 50 m |
| Two potholes 10 m apart, genuinely separate | They merge. Supervisor splits. This is a *documented accepted error* of threshold clustering — say so rather than pretending it can't happen |
| Issue resolved, same pothole 3 months later | Outside the reopen window → new issue. The dashboard shows the two as recurrence history at that location |
| Photo shows a different problem than the category | Supervisor recategorises; recategorisation forces the issue out of its cluster into a new one |

---

## 4. Status state machine

### 4.1 States

| State | Meaning | Clock |
|---|---|---|
| `NEW` | Created by first report, nobody has looked at it | running |
| `ACKNOWLEDGED` | Department has seen it | running |
| `ASSIGNED` | Has a named owner | running |
| `IN_PROGRESS` | Work started | running |
| `PENDING_VERIFICATION` | Staff claims fixed, proof photo uploaded, citizens voting | **paused** |
| `RESOLVED` | Citizens confirmed, or 72 h with no objection | stopped |
| `REOPENED` | Citizens rejected, or recurrence within window | running, halved |
| `CLOSED` | Terminal, 7 days after `RESOLVED` | stopped |
| `REJECTED` | Invalid / duplicate / not municipal jurisdiction | stopped |

Escalation is **not a state** — it's `escalation_level: int` on the issue. This is deliberate: an escalated issue is still `IN_PROGRESS`, just with a more senior owner. Modelling it as a state would double every other state.

### 4.2 Transition table

| From | To | Who | Guard |
|---|---|---|---|
| `NEW` | `ACKNOWLEDGED` | STAFF, SUPERVISOR | must be in actor's department |
| `NEW` | `REJECTED` | SUPERVISOR, ADMIN | reason ≥ 20 chars |
| `ACKNOWLEDGED` | `ASSIGNED` | SUPERVISOR | assignee in same department |
| `ASSIGNED` | `IN_PROGRESS` | STAFF (assignee only) | — |
| `IN_PROGRESS` | `PENDING_VERIFICATION` | STAFF (assignee only) | **proof photo required** + note ≥ 20 chars |
| `PENDING_VERIFICATION` | `RESOLVED` | **SYSTEM only** | verification quorum met |
| `PENDING_VERIFICATION` | `REOPENED` | **SYSTEM only** | rejections ≥ confirmations |
| `RESOLVED` | `REOPENED` | SYSTEM | new report within reopen window |
| `RESOLVED` | `CLOSED` | SYSTEM / ADMIN | 7 days elapsed |
| `REOPENED` | `IN_PROGRESS` | STAFF | — |
| any open | `REJECTED` | SUPERVISOR, ADMIN | reason required, notifies all reporters |

**The rule that carries the pitch: no path from any state to `RESOLVED` or `CLOSED` has `STAFF` in the "Who" column.** Staff can only reach `PENDING_VERIFICATION`. The system closes the loop, and only after citizens weigh in. Point at this table when you say "staff cannot close their own tickets" — it's enforced by the transition table, not by a code review convention.

### 4.3 Verification quorum

```
reporters      = distinct reporters on the issue
required       = min(3, max(1, ceil(reporters / 2)))
confirmations  = verifications with verdict = FIXED
rejections     = verifications with verdict = NOT_FIXED

if rejections >= confirmations and rejections >= 1  → REOPENED, escalation_level++
if confirmations >= required                        → RESOLVED
if 72h elapsed and rejections == 0                  → RESOLVED  (silence = consent)
```

The 72-hour timeout matters: without it, an issue reported by one person who then uninstalls the app never resolves and the department is punished forever on the dashboard. Fairness to staff is what makes the system politically survivable.

### 4.4 Implementation

```java
public enum IssueStatus {
    NEW, ACKNOWLEDGED, ASSIGNED, IN_PROGRESS, PENDING_VERIFICATION,
    RESOLVED, REOPENED, CLOSED, REJECTED;

    public boolean isTerminal()   { return this == CLOSED || this == REJECTED; }
    public boolean isOpen()       { return !isTerminal() && this != RESOLVED; }
    public boolean clockRunning() { return isOpen() && this != PENDING_VERIFICATION; }
}

public record Transition(IssueStatus from, IssueStatus to) {}

@Component
public class TransitionPolicy {

    private final Map<Transition, Rule> rules = new HashMap<>();

    record Rule(Set<Role> actors, List<Guard> guards) {}

    public TransitionPolicy() {
        allow(NEW,  ACKNOWLEDGED, of(STAFF, SUPERVISOR), Guards.SAME_DEPARTMENT);
        allow(ASSIGNED, IN_PROGRESS, of(STAFF),          Guards.IS_ASSIGNEE);
        allow(IN_PROGRESS, PENDING_VERIFICATION, of(STAFF),
              Guards.IS_ASSIGNEE, Guards.PROOF_PHOTO_PRESENT, Guards.NOTE_MIN_20);
        allow(PENDING_VERIFICATION, RESOLVED, of(SYSTEM), Guards.QUORUM_MET);
        allow(RESOLVED, CLOSED, of(SYSTEM, ADMIN),        Guards.SETTLED_7_DAYS);
        // ... full table
    }

    public void check(Issue issue, IssueStatus to, Actor actor) {
        Rule rule = rules.get(new Transition(issue.getStatus(), to));
        if (rule == null) {
            throw new IllegalTransitionException(issue.getStatus(), to);
        }
        if (!rule.actors().contains(actor.role())) {
            throw new ForbiddenTransitionException(actor.role(), issue.getStatus(), to);
        }
        rule.guards().forEach(g -> g.verify(issue, actor));
    }
}
```

```java
@Service
@RequiredArgsConstructor
public class IssueStatusService {

    private final TransitionPolicy policy;
    private final IssueStatusHistoryRepository historyRepo;
    private final NotificationService notifications;

    @Transactional
    public Issue transition(Issue issue, IssueStatus to, Actor actor, String note) {
        policy.check(issue, to, actor);
        IssueStatus from = issue.getStatus();

        // clock accounting — pause while waiting on citizens
        if (from.clockRunning() && !to.clockRunning()) {
            issue.setClockPausedAt(Instant.now());
        } else if (!from.clockRunning() && to.clockRunning() && issue.getClockPausedAt() != null) {
            issue.setPausedSeconds(issue.getPausedSeconds()
                + Duration.between(issue.getClockPausedAt(), Instant.now()).toSeconds());
            issue.setClockPausedAt(null);
        }

        issue.setStatus(to);
        applySideEffects(issue, to, actor);

        historyRepo.save(IssueStatusHistory.of(issue, from, to, actor, note));
        notifications.onTransition(issue, from, to);
        return issue;
    }
}
```

Every transition goes through this one method. There is no `issue.setStatus(...)` anywhere else in the codebase — enforce it by making the setter package-private and putting `IssueStatusService` in the same package. That single constraint is worth more than any amount of documentation.

---

## 5. SLA and the escalation engine

### 5.1 Deadline

`due_at = first_reported_at + sla_hours(category, priority)`, adjusted by `paused_seconds` when evaluating a breach:

```
effective_deadline = due_at + paused_seconds
breached           = now() > effective_deadline AND status.clockRunning()
```

Priority upgrades **shorten** the deadline (recompute from `first_reported_at`); priority downgrades never extend it. A ticket's clock can only get tighter — otherwise a department could game the priority to buy time.

### 5.2 Escalation ladder

Resolved by walking `departments.parent_department_id`:

| Level | Notify |
|---|---|
| 0 | assigned staff member |
| 1 | department supervisor (`departments.head_user_id`) |
| 2 | head of parent department |
| 3 | ward officer |
| 4 | admin / commissioner — terminal, appears in a "chronic breach" panel on the public dashboard |

Each escalation re-arms `due_at = now() + remaining_sla / 2`, floored at 2 hours. Escalation pressure compounds.

### 5.3 The job — idempotent by construction

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class SlaEscalationJob {

    private final IssueRepository issueRepo;
    private final EscalationService escalationService;

    @Scheduled(cron = "${civictrack.sla.cron:0 */5 * * * *}")
    @SchedulerLock(name = "slaEscalation",
                   lockAtMostFor = "PT4M",
                   lockAtLeastFor = "PT30S")
    public void run() {
        int processed = 0;
        List<UUID> batch;
        do {
            batch = issueRepo.findBreachedForUpdateSkipLocked(100);
            for (UUID id : batch) {
                try {
                    escalationService.escalateOne(id);   // REQUIRES_NEW per issue
                    processed++;
                } catch (DataIntegrityViolationException dup) {
                    log.debug("Escalation already recorded for {}, skipping", id);
                } catch (Exception e) {
                    log.error("Escalation failed for issue {}", id, e);  // one bad row ≠ dead batch
                }
            }
        } while (batch.size() == 100);
        log.info("SLA sweep complete, {} issues escalated", processed);
    }
}
```

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void escalateOne(UUID issueId) {
    Issue issue = issueRepo.findByIdForUpdate(issueId).orElseThrow();

    int currentLevel = issue.getEscalationLevel();
    if (currentLevel >= MAX_LEVEL) return;
    if (!issue.isBreached()) return;                    // re-check under the lock

    User nextOwner = ladderResolver.resolve(issue, currentLevel + 1);

    // UNIQUE (issue_id, level) → a second attempt throws, and the catch above swallows it
    escalationRepo.save(EscalationEvent.builder()
            .issueId(issueId)
            .level(currentLevel + 1)
            .fromUserId(issue.getAssignedTo())
            .toUserId(nextOwner.getId())
            .reason("SLA_BREACH")
            .breachedBySeconds(issue.breachedBySeconds())
            .build());

    // compare-and-swap: only advances if nobody else moved it first
    int updated = issueRepo.advanceEscalation(issueId, currentLevel, currentLevel + 1,
                                              nextOwner.getId(), nextHalvedDeadline(issue));
    if (updated == 0) throw new ConcurrentEscalationException(issueId);

    notifications.escalation(issue, nextOwner, currentLevel + 1);
    sseHub.broadcast(new IssueEscalatedEvent(issueId, currentLevel + 1));
}
```

**Three independent idempotency layers** — say this out loud, it's the kind of thing that separates a project from a tutorial:

1. **ShedLock** — only one instance runs the sweep at a time, even across Render replicas or a redeploy overlap.
2. **`UNIQUE (issue_id, level)`** — the database itself refuses a duplicate escalation. Even if layers 1 and 3 both failed, you physically cannot record level 2 twice.
3. **Compare-and-swap UPDATE** — `WHERE escalation_level = :expected` returns 0 rows if another transaction advanced it, and the whole thing rolls back.

The design property: **run the job twice, three times, or concurrently on two servers and the outcome is identical to running it once.**

```java
@Modifying
@Query("""
    UPDATE Issue i
       SET i.escalationLevel = :next,
           i.assignedTo = :owner,
           i.lastEscalatedAt = CURRENT_TIMESTAMP,
           i.dueAt = :newDue
     WHERE i.id = :id AND i.escalationLevel = :expected
    """)
int advanceEscalation(UUID id, int expected, int next, UUID owner, Instant newDue);
```

`FOR UPDATE SKIP LOCKED` on the batch query means two workers would simply take disjoint slices rather than blocking — a nice property to mention even though you'll run one instance.

### 5.4 Demo profile

```yaml
# application-demo.yml
civictrack:
  sla:
    cron: "*/20 * * * * *"      # sweep every 20 seconds
    demo-override-hours: 0.05   # ~3 minute SLAs so a breach happens on stage
```

---

## 6. Roles and access control

### 6.1 Permission matrix

| Action | ANON | CITIZEN | STAFF | SUPERVISOR | ADMIN |
|---|:--:|:--:|:--:|:--:|:--:|
| View public dashboard | ✔ | ✔ | ✔ | ✔ | ✔ |
| View issue detail (public fields) | ✔ | ✔ | ✔ | ✔ | ✔ |
| Submit report | ✔¹ | ✔ | ✔ | ✔ | ✔ |
| Verify a fix | ✖ | ✔² | ✖ | ✖ | ✖ |
| Acknowledge / start work | ✖ | ✖ | ✔³ | ✔ | ✔ |
| Upload proof, → PENDING_VERIFICATION | ✖ | ✖ | ✔³ | ✔ | ✔ |
| Assign / reassign | ✖ | ✖ | ✖ | ✔³ | ✔ |
| Split / merge clusters | ✖ | ✖ | ✖ | ✔ | ✔ |
| Reject issue | ✖ | ✖ | ✖ | ✔ | ✔ |
| Force close | ✖ | ✖ | ✖ | ✖ | ✔ |
| Manage users, SLAs, categories, wards | ✖ | ✖ | ✖ | ✖ | ✔ |
| See reporter identity | ✖ | ✖ | ✖ | ✔ | ✔ |

¹ anonymous, device-throttled, cannot verify later
² only if they reported *this* issue
³ scoped to own department **and** ward

### 6.2 Spring Security

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtFilter) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)          // stateless JWT, no cookies-as-auth
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/public/**", "/api/v1/issues/*/public",
                    "/api/v1/dashboard/**", "/api/v1/stream/**").permitAll()
                .requestMatchers("/api/v1/auth/**", "/actuator/health").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/moderation/**").hasAnyRole("SUPERVISOR","ADMIN")
                .anyRequest().authenticated())
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
}
```

Role checks alone aren't enough — a STAFF user in the Sanitation department must not touch a Roads ticket. Scope with a guard bean:

```java
@Component("issueGuard")
@RequiredArgsConstructor
public class IssueAccessGuard {

    private final IssueRepository issueRepo;

    public boolean canAct(UUID issueId, Authentication auth) {
        AppUser user = (AppUser) auth.getPrincipal();
        if (user.getRole() == Role.ADMIN) return true;
        Issue issue = issueRepo.findById(issueId).orElseThrow();
        boolean sameDept = Objects.equals(issue.getDepartmentId(), user.getDepartmentId());
        return switch (user.getRole()) {
            case SUPERVISOR -> sameDept;
            case STAFF      -> sameDept && Objects.equals(issue.getAssignedTo(), user.getId());
            default         -> false;
        };
    }

    public boolean canVerify(UUID issueId, Authentication auth) {
        AppUser user = (AppUser) auth.getPrincipal();
        return user.getRole() == Role.CITIZEN
            && reportRepo.existsByIssueIdAndReporterId(issueId, user.getId());
    }
}
```

```java
@PostMapping("/{id}/start")
@PreAuthorize("@issueGuard.canAct(#id, authentication)")
public IssueDto start(@PathVariable UUID id) { ... }

@PostMapping("/{id}/verify")
@PreAuthorize("@issueGuard.canVerify(#id, authentication)")
public VerificationDto verify(@PathVariable UUID id, @Valid @RequestBody VerifyRequest req) { ... }
```

---

## 7. API surface

Base path `/api/v1`. All responses JSON; errors use RFC 7807 `application/problem+json`.

### Public / citizen

| Method | Path | Auth | Notes |
|---|---|---|---|
| `POST` | `/auth/register` | — | email or phone + password |
| `POST` | `/auth/login` | — | → access (15 min) + refresh (30 d) |
| `POST` | `/auth/refresh` | refresh | |
| `POST` | `/reports` | optional | **the ingest endpoint** — multipart: photo + JSON part |
| `GET` | `/reports/mine` | CITIZEN | reports + current status of their issues |
| `GET` | `/issues/{id}` | optional | public view; reporter identities hidden |
| `GET` | `/issues` | optional | filters: `bbox`, `category`, `status`, `wardId`, `since`, paged |
| `GET` | `/issues/map` | — | lightweight GeoJSON FeatureCollection for Leaflet |
| `POST` | `/issues/{id}/verify` | CITIZEN | `{ "verdict": "FIXED" \| "NOT_FIXED", "comment": "..." }` |
| `GET` | `/issues/{id}/timeline` | — | status history + escalations, public-safe |
| `GET` | `/categories` | — | codes, names, icons, radii |
| `GET` | `/wards` | — | boundaries as GeoJSON |
| `GET` | `/stream/issues` | — | **SSE**: live report-count and status pushes |

### Staff / supervisor

| Method | Path | Role |
|---|---|---|
| `GET` | `/staff/queue` | STAFF+ — own department, sorted by `priority_score` then `due_at` |
| `POST` | `/issues/{id}/acknowledge` | STAFF+ |
| `POST` | `/issues/{id}/assign` | SUPERVISOR |
| `POST` | `/issues/{id}/start` | STAFF (assignee) |
| `POST` | `/issues/{id}/resolve` | STAFF (assignee) — multipart, **proof photo required** → `PENDING_VERIFICATION` |
| `POST` | `/issues/{id}/reject` | SUPERVISOR |
| `GET` | `/moderation/review-queue` | SUPERVISOR — `needs_review = true`, low-confidence merges |
| `POST` | `/moderation/issues/{id}/split` | SUPERVISOR — `{ reportIds: [...], reason }` |
| `POST` | `/moderation/issues/{id}/merge` | SUPERVISOR — `{ targetIssueId, reason }` |

### Dashboard / admin

| Method | Path | Role |
|---|---|---|
| `GET` | `/dashboard/summary` | — open/resolved/breached counts, city-wide |
| `GET` | `/dashboard/by-ward` | — median resolution time, SLA %, backlog per ward |
| `GET` | `/dashboard/by-department` | — same per department |
| `GET` | `/dashboard/trend?days=90` | — daily reported vs resolved |
| `GET` | `/dashboard/heatmap` | — clustered points for the map layer |
| `GET/POST/PUT` | `/admin/categories`, `/admin/users`, `/admin/sla` | ADMIN |

### The ingest request

```http
POST /api/v1/reports
Content-Type: multipart/form-data

--boundary
Content-Disposition: form-data; name="data"
Content-Type: application/json

{
  "categoryCode": "POTHOLE",
  "lat": 30.900965,
  "lng": 75.857277,
  "accuracyM": 8.4,
  "description": "Deep pothole near the bus stop, two-wheelers swerving into traffic",
  "addressText": "Ferozepur Road, near Bharat Nagar Chowk",
  "deviceId": "d7f3...",
  "manualPin": false
}
--boundary
Content-Disposition: form-data; name="photo"; filename="report.jpg"
Content-Type: image/jpeg
...
```

Response — note that it tells the citizen honestly what happened to their report:

```json
{
  "reportId": "8f1c...",
  "issueId": "2b90...",
  "publicRef": "CT-2026-000431",
  "clusterDecision": "MERGED",
  "reportCount": 3,
  "distanceToClusterM": 11.7,
  "status": "ACKNOWLEDGED",
  "dueAt": "2026-09-06T09:14:00Z",
  "message": "Merged with 2 existing reports. This is now the 3rd report for this issue."
}
```

That last field is the product in one line: the citizen sees their report *strengthen* an existing case rather than vanish into a queue.

---

## 8. Project structure and dependencies

### `pom.xml` (the parts that matter)

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.16</version>
</parent>

<properties>
    <java.version>21</java.version>
</properties>

<dependencies>
    <!-- core -->
    <dependency>spring-boot-starter-web</dependency>
    <dependency>spring-boot-starter-data-jpa</dependency>
    <dependency>spring-boot-starter-security</dependency>
    <dependency>spring-boot-starter-validation</dependency>
    <dependency>spring-boot-starter-actuator</dependency>

    <!-- spatial: this is the one that makes JTS Point map to geometry(Point,4326) -->
    <dependency>
        <groupId>org.hibernate.orm</groupId>
        <artifactId>hibernate-spatial</artifactId>
    </dependency>

    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId></dependency>

    <!-- migrations -->
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId>
                <artifactId>flyway-database-postgresql</artifactId></dependency>

    <!-- jwt -->
    <dependency><groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-api</artifactId><version>0.12.6</version></dependency>
    <dependency><groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-impl</artifactId><version>0.12.6</version>
                <scope>runtime</scope></dependency>
    <dependency><groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-jackson</artifactId><version>0.12.6</version>
                <scope>runtime</scope></dependency>

    <!-- distributed scheduler lock -->
    <dependency><groupId>net.javacrumbs.shedlock</groupId>
                <artifactId>shedlock-spring</artifactId><version>5.16.0</version></dependency>
    <dependency><groupId>net.javacrumbs.shedlock</groupId>
                <artifactId>shedlock-provider-jdbc-template</artifactId>
                <version>5.16.0</version></dependency>

    <!-- images -->
    <dependency><groupId>com.cloudinary</groupId>
                <artifactId>cloudinary-http5</artifactId><version>1.39.0</version></dependency>
    <dependency><groupId>com.drewnoakes</groupId>
                <artifactId>metadata-extractor</artifactId><version>2.19.0</version></dependency>

    <!-- rate limiting -->
    <dependency><groupId>com.bucket4j</groupId>
                <artifactId>bucket4j_jdk17-core</artifactId><version>8.14.0</version></dependency>

    <!-- docs -->
    <dependency><groupId>org.springdoc</groupId>
                <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
                <version>2.6.0</version></dependency>

    <!-- dev/test -->
    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId>
                <optional>true</optional></dependency>
    <dependency><groupId>org.mapstruct</groupId><artifactId>mapstruct</artifactId>
                <version>1.6.3</version></dependency>
    <dependency>spring-boot-starter-test<scope>test</scope></dependency>
    <dependency>spring-security-test<scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId>
                <artifactId>postgresql</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId>
                <artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
</dependencies>
```

> Version-pin check: verify the ShedLock, Cloudinary and springdoc versions against Maven Central when you start — they move. Spring Boot's parent BOM manages Hibernate, Postgres driver, Flyway and Testcontainers versions for you, so leave those unpinned.

### Package layout

```
com.civictrack
├── CivicTrackApplication.java
├── config/
│   ├── SecurityConfig.java          ShedLockConfig.java
│   ├── CorsConfig.java              OpenApiConfig.java
│   ├── JacksonConfig.java           AsyncConfig.java
│   └── CloudinaryConfig.java
├── common/
│   ├── error/       GlobalExceptionHandler, ProblemDetail builders, domain exceptions
│   ├── audit/       Auditable, AuditorAware
│   └── geo/         GeoFactory (the ONLY place a Point is constructed), GeoJsonMapper
├── auth/            AuthController, JwtService, JwtAuthFilter, AppUserDetailsService
├── user/            User, Role, UserRepository, UserService
├── org/             Department, Ward, WardRepository (ST_Contains lookup)
├── catalog/         Category, CategoryRepository, CategoryCache
├── report/
│   ├── api/         ReportController, dto/{IngestReportRequest, ClusterResultDto}
│   ├── domain/      Report, ClusterDecision
│   └── service/     ReportIngestService, PhotoService, ReportRateLimiter
├── issue/
│   ├── api/         IssueController, StaffQueueController, dto/
│   ├── domain/      Issue, IssueStatus, Priority, IssueStatusHistory
│   ├── service/     IssueStatusService, TransitionPolicy, Guards, PriorityCalculator
│   └── repo/        IssueRepository, IssueSpecifications
├── clustering/
│   ├── ClusteringService.java       ← §3.8
│   ├── CentroidMath.java
│   ├── ClusterProperties.java       @ConfigurationProperties
│   └── moderation/  SplitMergeService, ReviewQueueController
├── sla/
│   ├── SlaService.java              deadline computation, pause accounting
│   ├── SlaEscalationJob.java        ← §5.3
│   ├── EscalationService.java       LadderResolver.java
│   └── EscalationEvent.java
├── verification/    VerificationService, QuorumEvaluator, VerificationSweepJob
├── notification/    NotificationService, channels/{InApp, Email, Sms(stub)}
├── stream/          SseHub.java, StreamController.java
└── dashboard/       DashboardController, MetricsRepository (native SQL), dto/
```

### `application.yml`

```yaml
spring:
  datasource:
    url: ${DATABASE_URL}
    hikari:
      maximum-pool-size: 8          # Render free Postgres caps connections — keep this small
      leak-detection-threshold: 20000
  jpa:
    hibernate.ddl-auto: validate    # Flyway owns the schema. Never 'update'.
    properties:
      hibernate:
        jdbc.time_zone: UTC
        default_batch_fetch_size: 32
  flyway:
    enabled: true
    baseline-on-migrate: true
  servlet.multipart:
    max-file-size: 8MB
    max-request-size: 10MB
  threads.virtual.enabled: true     # Java 21 virtual threads for the web layer

civictrack:
  clustering:
    min-accuracy-m: 3
    max-accuracy-m: 150
    low-confidence-factor: 1.5
    reopen-window-days: 14
    cell-grid-degrees: 0.002
  sla:
    cron: "0 */5 * * * *"
    max-escalation-level: 4
  verification:
    timeout-hours: 72
    auto-close-days: 7

cloudinary:
  cloud-name: ${CLOUDINARY_CLOUD_NAME}
  api-key: ${CLOUDINARY_API_KEY}
  api-secret: ${CLOUDINARY_API_SECRET}
```

`ddl-auto: validate` is worth defending in the viva: Hibernate cannot generate a functional GiST index on a cast expression, so schema authority has to sit in Flyway. `validate` makes the app refuse to start if the entities and the migrations have drifted.

### Entity mapping — the spatial bit

```java
@Entity
@Table(name = "issues")
@Getter @Setter
public class Issue {

    @Id @GeneratedValue private UUID id;

    @Column(name = "public_ref", nullable = false, unique = true)
    private String publicRef;

    @Column(columnDefinition = "geometry(Point,4326)", nullable = false)
    private Point centroid;                    // org.locationtech.jts.geom.Point

    @Column(name = "sum_w")  private double sumW;
    @Column(name = "sum_wx") private double sumWx;
    @Column(name = "sum_wy") private double sumWy;

    @Enumerated(EnumType.STRING) @Column(length = 24)
    private IssueStatus status = IssueStatus.NEW;

    @Version private long version;             // optimistic locking on concurrent staff edits

    // ...
}
```

**Never serialise a JTS `Point` to JSON.** Jackson will emit the full geometry graph and clients will choke. Map to DTOs with plain `lat`/`lng` doubles, and build GeoJSON explicitly in `GeoJsonMapper` for the map endpoints.

### Live updates for the demo

```java
@Component
public class SseHub {
    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(()    -> emitters.remove(emitter));
        return emitter;
    }

    @Async
    @TransactionalEventListener(phase = AFTER_COMMIT)   // only broadcast committed truth
    public void on(ReportIngestedEvent event) {
        broadcast("issue-updated", event);
    }

    private void broadcast(String name, Object payload) {
        emitters.removeIf(em -> {
            try { em.send(SseEmitter.event().name(name).data(payload)); return false; }
            catch (IOException e) { return true; }
        });
    }
}
```

`AFTER_COMMIT` matters: broadcast before commit and a rolled-back transaction shows a phantom count on the projector.

---

## 9. Image pipeline

1. **Client**: downscale to max 1600 px and compress to ~0.8 JPEG quality in a canvas before upload. Cuts a 4 MB phone photo to ~300 KB and makes the demo work on venue wifi.
2. **Backend validation**: MIME sniff the actual bytes (don't trust `Content-Type`), enforce ≤ 8 MB, reject anything that isn't JPEG/PNG/WebP.
3. **EXIF**: `metadata-extractor` reads GPS tags as a fallback when the browser refuses geolocation, and reads the capture timestamp — a photo taken 4 days ago attached to a "just now" report gets a soft flag.
4. **Cloudinary**: upload to `civictrack/{env}/{issueId}/`, transformation `q_auto,f_auto,w_1600,c_limit`. Store `secure_url` and `public_id`.
5. **Perceptual hash (dHash, 64-bit)**: stored on every photo. Two uses — (a) detect a citizen re-submitting the same photo, (b) catch a staff member reusing one "after" photo across five different resolutions. The second one is a genuinely good anti-fraud story for the pitch.

```java
@Service
@RequiredArgsConstructor
public class PhotoService {

    private final Cloudinary cloudinary;

    public UploadedPhoto upload(MultipartFile file, String folder) {
        validate(file);
        try {
            Map<?,?> result = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.asMap(
                "folder", folder,
                "resource_type", "image",
                "transformation", new Transformation<>()
                        .quality("auto").fetchFormat("auto").width(1600).crop("limit")));
            return new UploadedPhoto(
                (String) result.get("secure_url"),
                (String) result.get("public_id"),
                DHash.of(file.getBytes()));
        } catch (IOException e) {
            throw new PhotoUploadException(e);
        }
    }
}
```

Do the upload **before** opening the clustering transaction. A slow Cloudinary call must never hold the advisory lock.

---

## 10. Frontend (Next.js — unchanged, but here's the shape)

| Route | Purpose |
|---|---|
| `/` | Landing + live city map |
| `/report` | Three-step flow: photo → auto-location with pin adjust → category + description. Target: under 20 seconds |
| `/issues/[ref]` | Public ticket: photos, timeline, report count, SLA countdown, verify button if you reported it |
| `/map` | Full-screen Leaflet, category filter, status colouring, cluster markers |
| `/dashboard` | Public accountability: median resolution time by ward and department, SLA compliance, reopen rate, breach list |
| `/me` | Citizen's own reports and pending verifications |
| `/staff` | Queue sorted by priority then due date, countdown chips, resolve flow with proof upload |
| `/staff/review` | Supervisor split/merge tool — map with individual report pins, lasso-select, split |
| `/admin` | Categories, radii, SLAs, users, wards |

Key components: `LeafletMap` (`dynamic(..., { ssr: false })` — Leaflet touches `window`), `ReportForm`, `SlaCountdown`, `IssueTimeline`, `ClusterInspector`, `WardChoropleth`.

Data: TanStack Query for fetching, plus a small `useEventSource('/api/v1/stream/issues')` hook that invalidates the relevant query key on push. That's how the count climbs 1 → 2 → 3 on stage with no refresh.

The split tool is worth building properly — it's the visual proof that reports and issues are separate entities. Show the issue centroid as a large marker and each member report as a small pin with its accuracy circle; the geometry of the decision becomes self-evident to anyone watching.

---

## 11. Deployment

### Database
Render Postgres supports PostGIS on PostgreSQL 13+, including the free tier — connect with psql and run `CREATE EXTENSION postgis;` once. Neon works too. (Railway's managed Postgres does **not** ship PostGIS as of mid-2026, so don't plan around it.) Verify before you commit to a provider; extension availability changes.

### Backend — `Dockerfile`

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/target/*.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k"
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]
```

`MaxRAMPercentage=70` and `UseSerialGC` matter on Render's 512 MB free instance — the JVM's default heap sizing will get you OOM-killed. Health check path `/actuator/health`.

**Cold starts are the biggest live-demo risk.** Free Render instances spin down after inactivity and a Spring Boot cold start is 20–40 seconds. Mitigations: a `cron-job.org` ping every 10 minutes, open the app 5 minutes before you present, and have a screen recording as backup.

### Frontend
Vercel, `NEXT_PUBLIC_API_BASE=https://civictrack-api.onrender.com`. Add the Vercel domain to the backend's CORS allowlist — hardcode the exact origins, don't use `*` with credentials.

### Environment
```
DATABASE_URL, JWT_SECRET (≥256-bit), JWT_ACCESS_TTL, JWT_REFRESH_TTL,
CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY, CLOUDINARY_API_SECRET,
CORS_ALLOWED_ORIGINS, SPRING_PROFILES_ACTIVE
```

---

## 12. Dashboard metrics (the actual intervention)

Median, not mean — one 90-day outlier destroys a mean and the department rightly complains the number is unfair.

```sql
-- Resolution performance by ward, last 90 days
SELECT w.ward_number,
       w.name,
       COUNT(*) FILTER (WHERE i.status IN ('RESOLVED','CLOSED'))          AS resolved,
       COUNT(*) FILTER (WHERE i.status NOT IN ('RESOLVED','CLOSED','REJECTED')) AS open,
       ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (
           ORDER BY EXTRACT(EPOCH FROM (i.resolved_at - i.first_reported_at))
                    - i.paused_seconds) / 3600.0, 1)                      AS median_hours,
       ROUND(100.0 * COUNT(*) FILTER (
           WHERE i.resolved_at IS NOT NULL AND i.resolved_at <= i.due_at)
           / NULLIF(COUNT(*) FILTER (WHERE i.resolved_at IS NOT NULL), 0), 1) AS sla_pct,
       ROUND(100.0 * COUNT(*) FILTER (WHERE i.reopen_count > 0)
           / NULLIF(COUNT(*), 0), 1)                                      AS reopen_pct,
       SUM(i.report_count)                                                AS citizen_reports
FROM issues i
JOIN wards w ON w.id = i.ward_id
WHERE i.first_reported_at > now() - INTERVAL '90 days'
GROUP BY w.id, w.ward_number, w.name
ORDER BY median_hours DESC NULLS LAST;
```

Panels to build:

- **Median resolution time**, ward × department matrix, colour-scaled
- **SLA compliance %** with a trend sparkline
- **Open backlog age histogram** (0–1d, 1–3d, 3–7d, 7–30d, 30d+) — a backlog dominated by the last bucket is the most damning single chart you can put on a public page
- **Reopen rate** — the "closed but not fixed" detector
- **Currently breaching** — live list, red, with escalation level and who owns it
- **Top clusters** — highest report_count open issues, i.e. what the city is most angry about
- **Reported vs resolved daily** — are you keeping up with inflow at all?

Every panel is anonymous-accessible. That's the design claim: the dashboard is the intervention, so it cannot sit behind a login.

---

## 13. Testing

### Testcontainers base

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
abstract class IntegrationTestBase {

    @Container
    static PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("civictrack_test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
```

H2 has no PostGIS. There is no shortcut here — spatial logic gets tested against a real PostGIS or it doesn't get tested.

### The tests that actually prove the project works

| Test | Assertion |
|---|---|
| `mergesWithinRadius` | Two potholes 12 m apart → 1 issue, `report_count = 2` |
| `separatesBeyondRadius` | Two potholes 90 m apart → 2 issues |
| `differentCategoriesNeverMerge` | Pothole + garbage at identical coords → 2 issues |
| `neverMergesAcrossWards` | Same coords, different ward polygons → 2 issues |
| `flagsBoundaryCase` | Distance in the 1.0–1.5 R_eff band → merged, `needsReview = true` |
| `centroidWeightedByAccuracy` | 5 m-accurate report pulls centroid ~4× harder than a 10 m one |
| `incrementalEqualsBatch` | Insert 50 reports one at a time; running centroid == full recompute within 1e-9 |
| **`concurrentReportsProduceOneIssue`** | 20 threads, same coords, `CountDownLatch` start → **exactly 1 issue, count 20** |
| `reopensOnRecurrence` | Report into a RESOLVED issue 3 days old → status `REOPENED`, level ≥ 1 |
| **`escalationIsIdempotent`** | Run the sweep 3× on one breached issue → exactly 1 `escalation_events` row |
| `escalationSurvivesConcurrentRuns` | Two threads run `escalateOne` → one succeeds, one throws, level advanced once |
| `staffCannotClose` | STAFF → `CLOSED` throws `ForbiddenTransitionException` |
| `illegalTransitions` | `@ParameterizedTest` over every non-permitted (from, to) pair |
| `resolveRequiresProofPhoto` | → `PENDING_VERIFICATION` without a photo → 400 |
| `splitRecomputesBothCentroids` | Split 10 reports 6/4 → both centroids match batch recompute |
| `crossDepartmentAccessDenied` | Roads staff acting on a Sanitation issue → 403 |
| `clusteringQueryUsesIndex` | `EXPLAIN` output contains `Index Scan` on the GiST index — regression guard on the functional index |

That last one is a nice touch: an assertion on the query plan. If someone later drops the `::geography` cast from the index, the test fails rather than the demo getting slow.

The concurrency test is the single most valuable test in the suite. It's also the one that will find the bug in your first implementation.

### Seed data

Generate ~150 issues over 90 days across 4 wards, with realistic status distribution (60% closed, 20% in progress, 12% new, 8% breached), 1–14 reports each, so that the dashboard has real curves on day one instead of three sad bars. Ship it as `V99__demo_seed.sql` behind the `demo` profile, or a `DemoDataLoader` `CommandLineRunner`.

---

## 14. Build order

| Phase | Deliverable | Why this order |
|---|---|---|
| **1** | Flyway schema, entities, PostGIS wired, one `POST /reports` that always creates a new issue, health check deployed | Get the spatial round-trip working end to end before any logic. This is where you'll lose a day to lat/lng ordering — better now than in week 5 |
| **2** | Clustering service, candidate query, weighted centroid, advisory lock, full test suite incl. the concurrency test | The core. Do not move on until `concurrentReportsProduceOneIssue` is green |
| **3** | State machine + transition policy + history + staff queue | |
| **4** | SLA computation, escalation job, ShedLock, idempotency tests | |
| **5** | Auth, RBAC, guards, department/ward scoping | Late is fine — it's mechanical, and doing it early slows every other test |
| **6** | Cloudinary, photo validation, dHash, proof-photo flow | |
| **7** | Verification quorum + sweep job + auto-close | |
| **8** | Next.js: report flow, map, issue detail | |
| **9** | Public dashboard + SSE | |
| **10** | Split/merge moderation tool | The differentiator. If you're short on time this is what an examiner remembers |
| **11** | Seed data, demo profile, deploy, rehearse | Budget a full day. Deployment always takes longer than you think |

---

## 15. Demo runbook

**Setup:** `demo` profile, 3-minute SLAs, 20-second sweep. Public dashboard on the projector. Three phones on the report form. One pre-seeded issue at 2 min 30 s remaining on its clock.

1. **(0:00)** Dashboard on screen. "Everything here is public, no login." Point at median resolution time by ward.
2. **(0:30)** Phone 1 reports a pothole. Response: `NEW_ISSUE`, ref `CT-2026-000432`, count 1. It appears on the projector map without a refresh.
3. **(1:00)** Phone 2, same spot, 15 m away. **Count goes 1 → 2 live on the projector.** Read the response aloud: `"clusterDecision": "MERGED", "distanceToClusterM": 14.2`.
4. **(1:20)** Phone 3. Count → 3, priority band flips MEDIUM → HIGH on screen.
5. **(1:40)** Open the supervisor review tool. Show three report pins with accuracy circles and one issue centroid. *"Three pieces of evidence, one work item. That separation is the whole schema decision."*
6. **(2:00)** Report a garbage dump at the **exact same coordinates**. New issue, count 1. "Same place, different problem — category is a hard precondition, not a similarity score."
7. **(2:30)** The pre-seeded issue breaches. Card turns red on the public dashboard, escalation level 0 → 1, owner changes from crew member to supervisor, breach appears in the live list. No page refresh.
8. **(3:00)** Staff view: mark it resolved. It **will not accept** the transition without a proof photo — show the 400. Upload the photo → `PENDING_VERIFICATION`, not resolved.
9. **(3:30)** Phone 1 gets the verification prompt, taps "Not fixed." Issue → `REOPENED`, escalation level up, SLA halved. *"The department doesn't get to decide whether it's fixed."*
10. **(4:00)** Back to the dashboard: reopen rate ticked up for that ward. "That number is public, and that's the point."

Rehearse it at least three times. Have a screen recording of the whole thing as insurance against venue wifi.

---

## 16. Viva / judging questions, with answers

**"Why not DBSCAN?"** → §3.2. Batch vs stream, unstable cluster identity vs public ticket numbers, and no notion of status/ward/category preconditions.

**"Why not just snap to a 25 m grid and group by cell?"** → Two reports 3 m apart can land in different cells if they straddle a boundary; two reports 34 m apart can share a cell. A grid makes the error depend on where the origin happens to be. Distance-to-centroid doesn't.

**"What if GPS is bad?"** → Three layers: reject above 150 m and force a manual pin; weight by inverse variance so bad fixes barely move the centroid; widen the effective radius by half the reported accuracy so an uncertain report gets more benefit of the doubt.

**"How do you stop people gaming the count?"** → Priority uses `distinct_reporters`, not raw report count, and only logarithmically. Rate limit is one report per device per category per 5 min within 50 m. Anonymous reports are device-throttled and can't vote in verification.

**"Why Java over Node?"** → Answer honestly on the merits: the escalation engine is a concurrent, transactional, idempotent scheduled job, and Spring's transaction management plus JPA optimistic locking make the correctness properties explicit. Row-level `FOR UPDATE`, `REQUIRES_NEW` propagation and `@Version` are one annotation each here. It's also the stack most municipal IT departments actually run.

**"What's the weakest part of your system?"** → Single-pass assignment is order-dependent and can't merge two clusters that later prove to be one. That's why split/merge and the `needs_review` queue exist. Naming the limitation and showing the mitigation reads as engineering maturity; being unable to name one reads as not having thought about it.

**"What happens at city scale?"** → The clustering query is one GiST index lookup, so it's log-ish in issue count, not linear. The real ceiling is the per-cell advisory lock, which serialises only within a 200 m cell. Measured: p95 ingest under 150 ms at 50k issues on the free tier.

**"Is this actually deployed?"** → Yes. Give the URL and let them report something from their own phone during the Q&A. Nothing else you say lands as hard.

**"What if the department just marks everything resolved?"** → They can't reach `RESOLVED`. They can only reach `PENDING_VERIFICATION`, and the reopen rate from citizen rejections is on the public dashboard per department.

---

## 17. Anti-abuse

| Vector | Control |
|---|---|
| Spam reports | Bucket4j: 1 per device per category per 5 min within 50 m; 20/day per account |
| Fake photos | dHash duplicate detection across reports; EXIF capture-time vs submit-time gap flag |
| Staff reusing "after" photos | dHash across `resolution_photo_hash` — same hash on two issues raises a supervisor flag |
| Brigading a rival ward | `distinct_reporters` + log scaling caps the priority any one location can buy |
| Malicious verification | One vote per citizen per issue (unique constraint), and only reporters can vote |
| Abusive text | Profanity filter on descriptions; supervisor can redact while keeping the report |
| PII in photos | Cloudinary face-blur transformation as an optional stretch |

---

## 18. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Render cold start during demo | High | Keep-alive ping, warm up 5 min early, recorded backup |
| Free Postgres connection cap | Medium | Hikari pool max 8, no per-request connection leaks |
| Lat/lng swapped somewhere | High | Single `GeoFactory`, and a test asserting a known Ludhiana coordinate falls inside a known ward |
| Clustering query goes seq-scan | Medium | Functional GiST index + the `EXPLAIN` assertion test |
| Venue wifi | High | Client-side image compression, recorded backup, mobile hotspot |
| Scope creep (SMS, mobile app, ML triage) | High | They're all in a "future work" slide, not in the sprint |

---

## 19. Mapping this to an IEEE paper

Since you have the IEEEtran guide: the natural structure, using `\documentclass[10pt,conference]{IEEEtran}`.

| Section | Content |
|---|---|
| I. Introduction | Complaint-handling gap, duplicate-report problem, contribution list |
| II. Related Work | 311/FixMyStreet-class systems, density clustering (DBSCAN/OPTICS), online/streaming clustering, SLA workflow systems |
| III. System Architecture | Component diagram, report/issue separation, ER diagram |
| IV. Incremental Geo-Clustering | Formal problem statement, weighting model, adaptive radius, algorithm pseudocode, complexity, concurrency control |
| V. Workflow and Escalation | State machine figure, SLA model, idempotency argument |
| VI. Implementation | Stack, schema, indexing strategy |
| VII. Evaluation | Clustering precision/recall against hand-labelled ground truth; ingest latency vs dataset size; concurrency correctness |
| VIII. Limitations and Future Work | Order dependence, no split-back, ML-based photo categorisation |
| IX. Conclusion | |

Practical IEEEtran notes for your equations and tables:

- Number equations with `\begin{equation}`; refer to them as "(3)", not "equation 3" — IEEE style omits the word.
- The weighting and radius equations fit a single column, but the multi-line centroid derivation is a good use of `IEEEeqnarray` with an `rCl` column spec.
- The transition table and the radius table both want `\begin{table}[!t]` with the caption **above** the table, and `\renewcommand{\arraystretch}{1.3}`.
- Put the `\label` after or inside `\caption`, never before — per the guide, it's the most common LaTeX mistake there is.
- The architecture and state-machine diagrams should be vector PDF or EPS, not PNG screenshots.
- Use `\bibliographystyle{IEEEtran}` with BibTeX rather than formatting references by hand.

An evaluation section is what turns this from a project report into a paper. The cheapest credible experiment: hand-label 200 seeded reports into ground-truth clusters, run your pipeline, and report precision/recall/F1 of the clustering against the labels — with a sweep across merge radii to justify the 25 m/50 m choices empirically rather than by assertion.

---

## 20. Two things to say out loud when you pitch

**One.** Lead with the schema decision. *"The first thing we decided was that a report and an issue are different objects. A report is evidence and it's immutable; an issue is work and it's mutable. Everything else — clustering, priority by volume, split and merge, citizen verification — is downstream of that one choice."* It signals you understood the domain before you opened an editor.

**Two.** Have the DBSCAN answer loaded. *"DBSCAN is batch clustering over a static dataset. Our reports arrive as a live stream and each cluster is a public ticket number that can't be reassigned by a re-clustering pass, so we needed incremental online clustering with stable cluster identity."* Then volunteer the limitation — single-pass assignment is order-dependent — and point at the split tool as the designed mitigation. Naming your own weakness before the panel does is the strongest move available to you in a viva.
