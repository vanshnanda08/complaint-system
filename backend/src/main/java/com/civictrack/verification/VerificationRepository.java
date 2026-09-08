package com.civictrack.verification;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Read-only access to citizen votes.
 *
 * <p>Phase 3 needs the counts, because the quorum guard is part of the
 * transition table. Casting a vote is phase 5's endpoint, so there is
 * deliberately no write method here yet -- a repository that can write before
 * anything is meant to is how an unaudited path gets added later.
 */
public interface VerificationRepository extends Repository<Verification, Long> {

    @Query(value = """
            SELECT COUNT(*) FROM verifications
            WHERE issue_id = :issueId AND verdict = 'FIXED'
            """, nativeQuery = true)
    int countConfirmations(@Param("issueId") UUID issueId);

    @Query(value = """
            SELECT COUNT(*) FROM verifications
            WHERE issue_id = :issueId AND verdict = 'NOT_FIXED'
            """, nativeQuery = true)
    int countRejections(@Param("issueId") UUID issueId);
}
