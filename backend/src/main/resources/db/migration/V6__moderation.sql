-- =====================================================================
-- V6: supervisor moderation -- split, merge, confirm, recategorise.
--
-- Leader clustering cannot undo its own mistakes: assignment is single-pass
-- and final. These are the designed escape hatch for that (see the class
-- comment on ClusteringService), and every use of them is logged here,
-- permanently, because a moderation action rewrites what the public map says
-- about where a problem is and how many people reported it.
--
-- No report is ever deleted by any of these. Reports move between issues and
-- keep their id, their photo, their reporter and their original clustering
-- audit columns. See DD-063.
-- =====================================================================

-- A merged-away issue is not deleted either: its public reference is in a
-- citizen's message history, so it must keep resolving to something. It is
-- closed out as REJECTED and points at the issue that now carries its reports.
ALTER TABLE issues
    ADD COLUMN merged_into_id UUID REFERENCES issues(id);

ALTER TABLE issues
    ADD CONSTRAINT chk_not_merged_into_self CHECK (merged_into_id IS NULL OR merged_into_id <> id);

CREATE TABLE moderation_actions (
    id               BIGSERIAL PRIMARY KEY,
    action           VARCHAR(16) NOT NULL
                     CHECK (action IN ('CONFIRM','SPLIT','MERGE','RECATEGORISE')),
    -- The issue the supervisor was looking at.
    issue_id         UUID NOT NULL REFERENCES issues(id),
    -- SPLIT: the new issue. MERGE: the issue merged away into issue_id.
    related_issue_id UUID REFERENCES issues(id),
    actor_id         UUID NOT NULL REFERENCES users(id),
    actor_role       VARCHAR(20) NOT NULL,
    -- Before-and-after geometry, the reports that moved and the clustering
    -- decision each one originally received, the categories either side of a
    -- recategorisation. JSON because the shape differs per action, and nothing
    -- queries inside it: it is a record to be read, not a table to be joined.
    detail           JSONB NOT NULL,
    note             TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_moderation_issue   ON moderation_actions (issue_id, created_at);
CREATE INDEX idx_moderation_related ON moderation_actions (related_issue_id)
    WHERE related_issue_id IS NOT NULL;
