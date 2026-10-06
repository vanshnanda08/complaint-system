package com.civictrack.verification;

import com.civictrack.issue.IssueRepository;
import com.civictrack.verification.VerificationService.SettleOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The settlement sweep, with no scheduling and no locking of its own.
 *
 * <p>Two jobs: settle every issue whose verification window has closed, and
 * close every issue that has stayed resolved for the full quiet period. Split
 * from {@link VerificationSweepJob} for the reason {@code SlaSweepService}
 * gives: the interesting idempotency claim is that a second run is a no-op
 * <em>without</em> ShedLock in front of it, and only an unlocked entry point
 * can prove that.
 *
 * <p>The idempotency comes from the per-issue transaction in
 * {@link VerificationService}: it locks the row and re-checks the status under
 * the lock, so a run that finds the issue already settled -- by a vote, or by a
 * concurrent sweep -- does nothing. A settled issue also leaves both worklists
 * by changing status, so a second run finds nothing to look at.
 *
 * <p><b>Not {@code @Transactional}</b>, so that each settlement commits on its
 * own and a failure in one leaves the rest of the batch intact.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VerificationSweepService {

    private static final int BATCH = 100;
    /** Bounds a sweep that somehow stops making progress. */
    private static final int MAX_ROUNDS = 100;

    private final IssueRepository issues;
    private final VerificationService verification;
    private final VerificationProperties props;
    private final Clock clock;

    public SweepResult sweep() {
        Instant now = clock.instant();
        Map<SettleOutcome, Integer> settled = settleTimedOut(now);
        int closed = autoClose(now);

        SweepResult result = new SweepResult(
                settled.getOrDefault(SettleOutcome.RESOLVED, 0),
                settled.getOrDefault(SettleOutcome.RESOLVED_WITHOUT_VERIFICATION, 0),
                settled.getOrDefault(SettleOutcome.REOPENED, 0),
                closed);
        log.info("Verification sweep complete: {} resolved, {} resolved without verification, "
                 + "{} reopened, {} closed", result.resolved(), result.resolvedWithoutVerification(),
                result.reopened(), result.closed());
        return result;
    }

    private Map<SettleOutcome, Integer> settleTimedOut(Instant now) {
        Instant cutoff = now.minus(Duration.ofHours(props.timeoutHours()));
        Map<SettleOutcome, Integer> outcomes = new EnumMap<>(SettleOutcome.class);

        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<UUID> batch = issues.findVerificationTimeoutIds(cutoff, BATCH);
            int progressed = 0;
            for (UUID id : batch) {
                SettleOutcome outcome = safely(() -> verification.settleTimedOut(id), id);
                outcomes.merge(outcome, 1, Integer::sum);
                if (outcome != SettleOutcome.STILL_OPEN) {
                    progressed++;
                }
            }
            // Every timed-out tally settles one way or the other (DD-060), so
            // STILL_OPEN here means a row that failed. Re-reading the same rows
            // would fail them again; stop instead.
            if (batch.size() < BATCH || progressed == 0) {
                break;
            }
        }
        return outcomes;
    }

    private int autoClose(Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(props.autoCloseDays()));
        int closed = 0;

        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<UUID> batch = issues.findAutoCloseIds(cutoff, BATCH);
            int progressed = 0;
            for (UUID id : batch) {
                try {
                    if (verification.autoClose(id)) {
                        progressed++;
                    }
                } catch (RuntimeException e) {
                    log.error("Auto-close failed for issue {}", id, e);
                }
            }
            closed += progressed;
            if (batch.size() < BATCH || progressed == 0) {
                break;
            }
        }
        return closed;
    }

    private SettleOutcome safely(Supplier<SettleOutcome> step, UUID id) {
        try {
            return step.get();
        } catch (RuntimeException e) {
            // One bad row must not kill a batch of a hundred.
            log.error("Verification settlement failed for issue {}", id, e);
            return SettleOutcome.STILL_OPEN;
        }
    }

    public record SweepResult(int resolved, int resolvedWithoutVerification, int reopened, int closed) {
    }
}
