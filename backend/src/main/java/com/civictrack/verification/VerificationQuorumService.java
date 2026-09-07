package com.civictrack.verification;

import com.civictrack.issue.Issue;
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
        boolean timedOut = issue.getClockPausedAt() != null
                && Duration.between(issue.getClockPausedAt(), now).toHours() >= props.timeoutHours();

        return new VerificationTally(
                issue.getDistinctReporterCount(),
                verifications.countConfirmations(issue.getId()),
                verifications.countRejections(issue.getId()),
                timedOut);
    }
}
