package com.civictrack.verification;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Citizen votes.
 *
 * <p>Phase 3 needed only the counts, because the quorum guard is part of the
 * transition table, and the repository deliberately had no write method until
 * something was meant to write. Phase 6 adds exactly one, used by exactly one
 * caller: {@link VerificationService#vote}, which takes the issue's row lock
 * first. A vote written from anywhere else would skip the eligibility checks
 * and the settlement that has to follow it.
 *
 * <p>Every count is per round (DD-059). Counting across rounds would hold the
 * first fix's rejections against the second.
 */
public interface VerificationRepository extends Repository<Verification, Long> {

    Verification saveAndFlush(Verification verification);

    @Query(value = """
            SELECT COUNT(*) FROM verifications
            WHERE issue_id = :issueId AND verification_round = :round AND verdict = 'FIXED'
            """, nativeQuery = true)
    int countConfirmations(@Param("issueId") UUID issueId, @Param("round") int round);

    @Query(value = """
            SELECT COUNT(*) FROM verifications
            WHERE issue_id = :issueId AND verification_round = :round AND verdict = 'NOT_FIXED'
            """, nativeQuery = true)
    int countRejections(@Param("issueId") UUID issueId, @Param("round") int round);

    /**
     * Issues waiting on this citizen: pending, reported by them, and not yet
     * answered in the current round. Oldest claim first, because that is the
     * one whose silence deadline is nearest.
     */
    @Query(value = """
            SELECT i.id FROM issues i
            WHERE i.status = 'PENDING_VERIFICATION'
              AND EXISTS (SELECT 1 FROM reports r
                          WHERE r.issue_id = i.id AND r.reporter_id = :citizenId)
              AND NOT EXISTS (SELECT 1 FROM verifications v
                              WHERE v.issue_id = i.id
                                AND v.verification_round = i.verification_round
                                AND v.citizen_id = :citizenId)
            ORDER BY i.clock_paused_at ASC
            """, nativeQuery = true)
    List<UUID> findAwaitingVerdictFrom(@Param("citizenId") UUID citizenId);

    Optional<Verification> findByIssueIdAndVerificationRoundAndCitizenId(
            UUID issueId, int verificationRound, UUID citizenId);
}
