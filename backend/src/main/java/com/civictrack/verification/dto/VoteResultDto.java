package com.civictrack.verification.dto;

import com.civictrack.issue.IssueStatus;
import com.civictrack.verification.Verdict;
import com.civictrack.verification.VerificationService;

/**
 * What a vote did. {@code status} is the issue's status after the vote, so
 * the client can say "your answer reopened this" without a second request.
 */
public record VoteResultDto(
        Verdict verdict,
        IssueStatus status,
        VerificationService.SettleOutcome outcome,
        int confirmations,
        int rejections,
        int required
) {
    public static VoteResultDto from(VerificationService.VoteResult r) {
        return new VoteResultDto(r.verdict(), r.status(), r.outcome(),
                r.tally().confirmations(), r.tally().rejections(), r.tally().required());
    }
}
