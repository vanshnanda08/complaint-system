-- =====================================================================
-- CivicTrack V1 baseline
--
-- Flyway owns this schema outright. Hibernate runs with ddl-auto=validate
-- and never generates DDL, because Hibernate cannot express a composite
-- GiST index over a cast expression, and that index is what keeps the
-- clustering candidate query in single-digit milliseconds.
--
-- Revision-2 columns carry an inline reference to the design decision that
-- introduced them. See docs/DESIGN-DECISIONS.md.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgcrypto;    -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS btree_gist;  -- scalar columns inside a GiST index

-- ---------------------------------------------------------------------
-- reference data
-- ---------------------------------------------------------------------

CREATE TABLE departments (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                 VARCHAR(40)  NOT NULL UNIQUE,
    name                 VARCHAR(160) NOT NULL,
    parent_department_id UUID REFERENCES departments(id),
    head_user_id         UUID,                        -- FK added after users exists
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE wards (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ward_number     INT          NOT NULL UNIQUE,
    name            VARCHAR(160) NOT NULL,
    boundary        geometry(MultiPolygon, 4326) NOT NULL,
    -- DD-005: level 3 of the escalation ladder is the ward officer, who is
    -- not a node in the department tree. The ladder reads this column
    -- directly rather than pretending the walk continues.
    officer_user_id UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_wards_boundary ON wards USING GIST (boundary);

CREATE TABLE categories (
    code                  VARCHAR(40) PRIMARY KEY,
    display_name          VARCHAR(120) NOT NULL,

    -- Standing rule 1: every tunable number lives here, not in code.
    merge_radius_m        INT     NOT NULL,
    default_sla_hours     INT     NOT NULL,
    severity_weight       INT     NOT NULL DEFAULT 10,
    reopen_window_days    INT     NOT NULL DEFAULT 14,

    -- DD-001: bound on cluster spatial extent, as a multiple of
    -- merge_radius_m. Prevents a moving centroid from chaining along a
    -- linear defect through a sequence of individually valid merges.
    max_extent_multiplier NUMERIC(4,2) NOT NULL DEFAULT 2.00,

    -- DD-002: what the low-confidence band does for THIS category.
    -- MERGE_FLAG optimistically merges and flags; SPLIT_FLAG creates a
    -- separate flagged issue. Safety-critical categories take SPLIT_FLAG,
    -- because a hidden duplicate open manhole is a hazard, not a counter.
    low_conf_action       VARCHAR(12) NOT NULL DEFAULT 'MERGE_FLAG'
                          CHECK (low_conf_action IN ('MERGE_FLAG','SPLIT_FLAG')),

    is_mobile_target      BOOLEAN NOT NULL DEFAULT FALSE,
    department_id         UUID REFERENCES departments(id),
    active                BOOLEAN NOT NULL DEFAULT TRUE,

    CONSTRAINT chk_merge_radius       CHECK (merge_radius_m > 0),
    CONSTRAINT chk_sla_hours          CHECK (default_sla_hours > 0),
    CONSTRAINT chk_extent_multiplier  CHECK (max_extent_multiplier >= 1.0)
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
    CONSTRAINT chk_user_contact CHECK (email IS NOT NULL OR phone IS NOT NULL)
);

ALTER TABLE departments
    ADD CONSTRAINT fk_dept_head FOREIGN KEY (head_user_id) REFERENCES users(id);
ALTER TABLE wards
    ADD CONSTRAINT fk_ward_officer FOREIGN KEY (officer_user_id) REFERENCES users(id);

-- ---------------------------------------------------------------------
-- core: issues (mutable work) and reports (immutable evidence)
-- ---------------------------------------------------------------------

-- public_ref is a citizen-facing ticket number. It is issued once and never
-- reassigned, which is the property that rules out re-clustering passes.
CREATE SEQUENCE issue_public_ref_seq;

CREATE TABLE issues (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    public_ref            VARCHAR(20)  NOT NULL UNIQUE,     -- CT-2026-000431
    category_code         VARCHAR(40)  NOT NULL REFERENCES categories(code),
    ward_id               UUID         NOT NULL REFERENCES wards(id),
    department_id         UUID         REFERENCES departments(id),

    status                VARCHAR(24)  NOT NULL DEFAULT 'NEW',
    priority              VARCHAR(12)  NOT NULL DEFAULT 'MEDIUM',
    priority_score        NUMERIC(8,2) NOT NULL DEFAULT 0,

    -- Derived cluster geometry. sum_w, sum_wx and sum_wy make the
    -- accuracy-weighted centroid an O(1) update per arriving report
    -- instead of a rescan of member reports.
    centroid              geometry(Point, 4326) NOT NULL,
    sum_w                 DOUBLE PRECISION NOT NULL,   -- SUM(w_i),        1/m^2
    sum_wx                DOUBLE PRECISION NOT NULL,   -- SUM(w_i * lon_i)
    sum_wy                DOUBLE PRECISION NOT NULL,   -- SUM(w_i * lat_i)

    -- DD-001: distance from the centroid to the furthest member report.
    -- Extends the O(1) principle to the extent cap: one column, one
    -- comparison, no rescan.
    max_member_dist_m     DOUBLE PRECISION NOT NULL DEFAULT 0,

    report_count          INT NOT NULL DEFAULT 0,
    distinct_reporter_count INT NOT NULL DEFAULT 0,
    cluster_confidence    VARCHAR(10) NOT NULL DEFAULT 'HIGH'
                          CHECK (cluster_confidence IN ('HIGH','LOW')),
    needs_review          BOOLEAN NOT NULL DEFAULT FALSE,
    review_reason         VARCHAR(40),

    -- SLA clock
    first_reported_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_reported_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    acknowledged_at       TIMESTAMPTZ,
    due_at                TIMESTAMPTZ NOT NULL,
    clock_paused_at       TIMESTAMPTZ,
    paused_seconds        BIGINT NOT NULL DEFAULT 0,
    -- DD-005: capped at 4. Every increment, from SLA breach and from
    -- reopen alike, goes through the same capped resolver.
    escalation_level      INT NOT NULL DEFAULT 0,
    last_escalated_at     TIMESTAMPTZ,

    assigned_to           UUID REFERENCES users(id),
    assigned_at           TIMESTAMPTZ,

    resolution_note       TEXT,
    resolution_photo_url  TEXT,
    resolution_photo_hash VARCHAR(64),   -- dHash, anti-fraud
    resolved_by           UUID REFERENCES users(id),
    resolved_at           TIMESTAMPTZ,
    closed_at             TIMESTAMPTZ,
    reopen_count          INT NOT NULL DEFAULT 0,

    -- DD-006: set when an issue resolves through the 72h timeout with zero
    -- votes cast. Not fixable without opening an abuse vector, so it is
    -- measured and published per department instead.
    resolved_without_verification BOOLEAN NOT NULL DEFAULT FALSE,

    rejected_reason       TEXT,
    version               BIGINT NOT NULL DEFAULT 0,   -- JPA @Version
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_status CHECK (status IN
      ('NEW','ACKNOWLEDGED','ASSIGNED','IN_PROGRESS','PENDING_VERIFICATION',
       'RESOLVED','REOPENED','CLOSED','REJECTED')),
    CONSTRAINT chk_priority CHECK (priority IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT chk_escalation_level CHECK (escalation_level BETWEEN 0 AND 4),
    CONSTRAINT chk_sum_w_positive   CHECK (sum_w > 0)
);

-- The workhorse index. btree_gist lets category_code and ward_id sit in the
-- same GiST index as the geography expression, so ONE index serves the whole
-- candidate query. The cast must be inside the index definition: without it
-- the planner falls back to a sequential scan and clustering degrades from
-- ~1 ms to ~400 ms at 50k issues. IssueRepositoryPlanTest asserts this.
CREATE INDEX idx_issues_cluster_candidates
    ON issues USING GIST (category_code, ward_id, (centroid::geography))
    WHERE status NOT IN ('CLOSED','REJECTED');

CREATE INDEX idx_issues_due ON issues (due_at)
    WHERE status NOT IN ('CLOSED','REJECTED','RESOLVED');
CREATE INDEX idx_issues_ward_status ON issues (ward_id, status);
CREATE INDEX idx_issues_needs_review ON issues (needs_review) WHERE needs_review;

CREATE TABLE reports (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    issue_id           UUID NOT NULL REFERENCES issues(id),
    reporter_id        UUID REFERENCES users(id),     -- null = anonymous
    device_id          VARCHAR(64),                   -- anon dedupe / rate limit
    category_code      VARCHAR(40) NOT NULL REFERENCES categories(code),

    location           geometry(Point, 4326) NOT NULL,
    gps_accuracy_m     DOUBLE PRECISION NOT NULL,
    manual_pin         BOOLEAN NOT NULL DEFAULT FALSE,
    address_text       VARCHAR(300),
    landmark           VARCHAR(200),
    description        TEXT,
    photo_url          TEXT NOT NULL,
    photo_hash         VARCHAR(64),

    -- Clustering audit trail: why did this report land where it landed?
    -- SPLIT_LOW_CONF and SPLIT_EXTENT_CAPPED are revision-2 outcomes
    -- (DD-002 and DD-001 respectively).
    cluster_decision   VARCHAR(24) NOT NULL
                       CHECK (cluster_decision IN
                         ('NEW_ISSUE','MERGED','MERGED_LOW_CONF',
                          'SPLIT_LOW_CONF','SPLIT_EXTENT_CAPPED','MANUAL')),
    cluster_distance_m DOUBLE PRECISION,
    effective_radius_m DOUBLE PRECISION,
    -- DD-001: the extent the target cluster WOULD have had if this report
    -- had been merged into it. Recorded whether or not the merge happened,
    -- so the extent-cap ablation in the evaluation can be run from the
    -- audit trail rather than by re-running the pipeline.
    projected_extent_m DOUBLE PRECISION,

    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_gps_accuracy CHECK (gps_accuracy_m > 0),
    CONSTRAINT chk_reporter_identity CHECK (reporter_id IS NOT NULL OR device_id IS NOT NULL)
);
CREATE INDEX idx_reports_issue    ON reports (issue_id);
CREATE INDEX idx_reports_location ON reports USING GIST ((location::geography));
CREATE INDEX idx_reports_reporter ON reports (reporter_id, created_at DESC);
CREATE INDEX idx_reports_device    ON reports (device_id, category_code, created_at DESC);

-- ---------------------------------------------------------------------
-- audit / workflow
-- ---------------------------------------------------------------------

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
    id                  BIGSERIAL PRIMARY KEY,
    issue_id            UUID NOT NULL REFERENCES issues(id),
    level               INT  NOT NULL,
    from_user_id        UUID REFERENCES users(id),
    to_user_id          UUID REFERENCES users(id),
    reason              VARCHAR(60) NOT NULL,   -- SLA_BREACH | REOPEN | MANUAL
    breached_by_seconds BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- This constraint IS the idempotency guarantee. Run the sweep twice,
    -- three times, or concurrently on two instances: the database itself
    -- refuses to record level 2 twice.
    CONSTRAINT uq_escalation UNIQUE (issue_id, level),
    CONSTRAINT chk_escalation_level_range CHECK (level BETWEEN 1 AND 4)
);

CREATE TABLE verifications (
    id         BIGSERIAL PRIMARY KEY,
    issue_id   UUID NOT NULL REFERENCES issues(id),
    citizen_id UUID NOT NULL REFERENCES users(id),
    verdict    VARCHAR(12) NOT NULL CHECK (verdict IN ('FIXED','NOT_FIXED')),
    comment    TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_verification UNIQUE (issue_id, citizen_id)   -- one vote each
);

CREATE TABLE notifications (
    id         BIGSERIAL PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES users(id),
    issue_id   UUID REFERENCES issues(id),
    type       VARCHAR(40)  NOT NULL,
    title      VARCHAR(200) NOT NULL,
    body       TEXT,
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notif_user ON notifications (user_id, read_at, created_at DESC);

CREATE TABLE shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
