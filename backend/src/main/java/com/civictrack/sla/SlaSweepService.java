package com.civictrack.sla;

import com.civictrack.issue.PriorityRecomputer;
import com.civictrack.sla.escalation.EscalationOutcome;
import com.civictrack.sla.escalation.EscalationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The sweep itself, with no scheduling and no locking of its own.
 *
 * <p>Splitting this from {@link SlaEscalationJob} is what makes the idempotency
 * claim testable. If the only entry point were the ShedLock-wrapped job, a test
 * that ran it twice and found one escalation event would have proved that
 * ShedLock works -- which it does, and which is not the interesting claim. The
 * interesting claim is that the <em>database</em> layers make a second run a
 * no-op, and proving it requires an entry point with no lock in front of it.
 * {@code EscalationIdempotencyIT} calls this one; {@code EscalationJobLockIT}
 * calls the job.
 *
 * <p><b>Not {@code @Transactional}, deliberately.</b> The batch query takes
 * {@code FOR UPDATE SKIP LOCKED} row locks, and each issue is then processed in
 * a {@code REQUIRES_NEW} transaction that locks the same row again on its own
 * connection. If this method held the first set of locks, that second lock
 * would wait for a transaction that cannot proceed until it returns -- a
 * self-deadlock that would present as a sweep that simply hangs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlaSweepService {

    private final com.civictrack.issue.IssueRepository issueRepo;
    private final EscalationService escalationService;
    private final PriorityRecomputer priorityRecomputer;
    private final SlaProperties props;
    private final Clock clock;

    public SweepResult sweep() {
        Instant now = clock.instant();
        Map<EscalationOutcome, Integer> outcomes = new EnumMap<>(EscalationOutcome.class);

        List<UUID> batch;
        int guard = 0;
        do {
            batch = issueRepo.lockBreachedIssueIds(now, props.maxEscalationLevel(), props.batchSize());
            int escalatedThisRound = 0;
            for (UUID id : batch) {
                EscalationOutcome outcome = escalateSafely(id, now);
                outcomes.merge(outcome, 1, Integer::sum);
                if (outcome == EscalationOutcome.ESCALATED) {
                    escalatedThisRound++;
                }
            }
            // An escalation re-arms the deadline, so an escalated issue drops
            // out of the next batch on its own. A round that escalates nothing
            // has therefore made no progress, and continuing would re-select
            // the same rows until the guard counter stopped it.
            if (escalatedThisRound == 0) {
                break;
            }
        } while (batch.size() == props.batchSize() && ++guard < 100);

        // DD-004. Runs whether or not anything escalated: the issues that most
        // need re-scoring are precisely the ones nothing has happened to.
        int rescored = priorityRecomputer.recomputeAll(now, props.batchSize());

        SweepResult result = new SweepResult(
                outcomes.getOrDefault(EscalationOutcome.ESCALATED, 0),
                outcomes.getOrDefault(EscalationOutcome.NOT_BREACHED, 0),
                outcomes.getOrDefault(EscalationOutcome.ALREADY_RECORDED, 0)
                        + outcomes.getOrDefault(EscalationOutcome.RACE_LOST, 0),
                rescored);
        log.info("SLA sweep complete: {} escalated, {} no longer breached, {} raced, {} rescored",
                result.escalated(), result.notBreached(), result.raced(), result.rescored());
        return result;
    }

    private EscalationOutcome escalateSafely(UUID id, Instant now) {
        try {
            return escalationService.escalateOne(id, now);
        } catch (DataIntegrityViolationException duplicate) {
            // UNIQUE (issue_id, level). Another worker got there first, which
            // is exactly what that constraint is for. Not an error.
            log.debug("Escalation already recorded for {}", id);
            return EscalationOutcome.ALREADY_RECORDED;
        } catch (RuntimeException e) {
            // One bad row must not kill a batch of a hundred.
            log.error("Escalation failed for issue {}", id, e);
            return EscalationOutcome.RACE_LOST;
        }
    }

    public record SweepResult(int escalated, int notBreached, int raced, int rescored) {
    }
}
