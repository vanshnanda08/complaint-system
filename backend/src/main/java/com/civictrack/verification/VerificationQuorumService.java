package com.civictrack.verification;

import com.civictrack.issue.Issue;
import com.civictrack.issue.IssueStatus;
import com.civictrack.issue.policy.VerificationTally;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Assembles the vote tally a transition guard needs.
 *
 * <p>The timeout is measured from when the issue entered PENDING_VERIFICATION
 * -- which is when the clock paused -- rather than from resolution or from the
 * first report. Any other origin would let an issue that spent a week in
 * progress arrive at verification already timed out, resolving it before a
 * single citizen had the chance to look.
 */
@Service
@RequiredArgsConstructor
public class VerificationQuorumService {

    private final VerificationRepository verifications;
    private final VerificationProperties props;

    @Transactional(readOnly = true)
    public VerificationTally tally(Issue issue, Instant now) {
        Instant deadline = silenceDeadline(issue);
        boolean timedOut = deadline != null && !now.isBefore(deadline);

        return new VerificationTally(
                issue.getDistinctReporterCount(),
                verifications.countConfirmations(issue.getId(), issue.getVerificationRound()),
                verifications.countRejections(issue.getId(), issue.getVerificationRound()),
                timedOut);
    }

    /**
     * The moment after which silence counts as consent, or null when the issue
     * is not waiting on citizens. The verify screen states it, and the tally
     * above is judged against it, so the two cannot disagree about when that
     * is.
     */
    public Instant silenceDeadline(Issue issue) {
        if (issue.getStatus() != IssueStatus.PENDING_VERIFICATION || issue.getClockPausedAt() == null) {
            return null;
        }
        return issue.getClockPausedAt().plus(Duration.ofHours(props.timeoutHours()));
    }
}
