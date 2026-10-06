-- =====================================================================
-- V5: verification rounds.
--
-- V1 declared UNIQUE (issue_id, citizen_id) on verifications: one vote per
-- citizen per issue, for the life of the issue. That is correct for exactly
-- one fix. It is wrong for the second.
--
-- A fix that citizens reject goes REOPENED -> IN_PROGRESS -> back to
-- PENDING_VERIFICATION with a new proof photo. Under the V1 constraint:
--   - every citizen who voted on the first fix is refused a vote on the
--     second, so the people best placed to judge it -- the ones who already
--     looked -- are exactly the ones locked out; and
--   - the tally counts every row for the issue, so the first round's
--     NOT_FIXED votes are still counted against the second fix, and the
--     timeout sweep reopens it again on evidence about a different repair.
--
-- So each entry into PENDING_VERIFICATION opens a new round. The issue
-- carries the current round; a vote records the round it was cast in; the
-- tally counts only the current one; and uniqueness is per round. See DD-059.
--
-- No row in verifications predates this migration in any environment --
-- nothing wrote to the table before phase 6 -- so the DEFAULT 1 on the new
-- column is a formality rather than a back-fill decision.
-- =====================================================================

ALTER TABLE issues
    ADD COLUMN verification_round INT NOT NULL DEFAULT 0;

-- Back-filled from the audit trail rather than guessed from
-- resolution_photo_url: an issue that has been submitted twice is in round
-- two, and only the history knows that.
UPDATE issues i
   SET verification_round = (SELECT COUNT(*)
                               FROM issue_status_history h
                              WHERE h.issue_id = i.id
                                AND h.to_status = 'PENDING_VERIFICATION');

ALTER TABLE verifications
    ADD COLUMN verification_round INT NOT NULL DEFAULT 1;

ALTER TABLE verifications
    DROP CONSTRAINT uq_verification;

-- Still one vote each -- per fix, not per issue.
ALTER TABLE verifications
    ADD CONSTRAINT uq_verification_round UNIQUE (issue_id, verification_round, citizen_id);

-- The settlement sweep's two worklists. Partial, so each index holds only the
-- handful of rows actually waiting, not the whole table.
CREATE INDEX idx_issues_pending_verification ON issues (clock_paused_at)
    WHERE status = 'PENDING_VERIFICATION';
CREATE INDEX idx_issues_resolved_at ON issues (resolved_at)
    WHERE status = 'RESOLVED';
