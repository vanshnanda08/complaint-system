package com.civictrack.verification.dto;

import com.civictrack.publicapi.dto.PublicIssueDto;
import com.civictrack.verification.Verdict;

import java.time.Instant;

/**
 * Everything the verify screen needs in one response (blueprint 3.12): the
 * issue, the before and after photos, this caller's standing and vote, the
 * tally in the current round, and when silence starts to count as consent.
 *
 * <p>{@code eligible} is false rather than the request being refused, so a
 * citizen who opens a link meant for somebody else is told why they cannot
 * answer instead of seeing an error. The vote endpoint still enforces it.
 *
 * <p>{@code myVerdict} is the caller's vote in the current round, present
 * after the vote has settled the issue too. A citizen returning to the screen
 * sees what they said and what happened, not an error (blueprint 3.12).
 */
public record VerificationViewDto(
        PublicIssueDto issue,
        String beforePhotoUrl,
        Instant submittedAt,
        Instant silenceDeadline,
        int round,
        boolean eligible,
        String ineligibleReason,
        Verdict myVerdict,
        String myReason,
        int confirmations,
        int rejections,
        int required
) {
}
