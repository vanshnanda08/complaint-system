package com.civictrack.sla.escalation;

import com.civictrack.category.Category;
import com.civictrack.category.CategoryRepository;
import com.civictrack.issue.Issue;
import com.civictrack.issue.IssueRepository;
import com.civictrack.sla.SlaProperties;
import com.civictrack.sla.SlaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Escalates one issue, idempotently.
 *
 * <p>Each issue is processed in its own transaction, so one bad row cannot
 * abort a batch of a hundred, and a rollback undoes exactly one escalation
 * rather than the whole sweep.
 *
 * <p>Three independent idempotency layers protect this, and they are
 * independent on purpose -- any one of them failing leaves the other two:
 * <ol>
 *   <li><b>ShedLock</b> on the job, so one instance sweeps at a time.</li>
 *   <li><b>{@code UNIQUE (issue_id, level)}</b>, so the database itself refuses
 *       to record a rung twice.</li>
 *   <li><b>The compare-and-swap update</b>, which advances the level only from
 *       the value this transaction read under its own row lock.</li>
 * </ol>
 * The property that follows: run the sweep twice, three times, or concurrently
 * on two instances and the outcome is identical to running it once.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EscalationService {

    private final IssueRepository issueRepo;
    private final EscalationEventRepository escalationRepo;
    private final CategoryRepository categoryRepo;
    private final EscalationLadder ladder;
    private final SlaService slaService;
    private final SlaProperties props;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EscalationOutcome escalateOne(UUID issueId, Instant now) {
        Issue issue = issueRepo.findByIdForUpdate(issueId).orElse(null);
        if (issue == null) {
            return EscalationOutcome.MISSING;
        }

        int currentLevel = issue.getEscalationLevel();
        if (ladder.isTerminal(currentLevel)) {
            return EscalationOutcome.TERMINAL;
        }

        // Re-checked under the lock, and not for the same reason as DD-003.
        // There the concern was a stale ordering; here it is that the batch
        // query ran seconds ago and the issue may have been acknowledged,
        // resolved or paused since. Escalating an issue that is no longer late
        // would be worse than missing one: it names an owner who did nothing
        // wrong.
        if (!slaService.isBreached(issue, now)) {
            return EscalationOutcome.NOT_BREACHED;
        }

        int nextLevel = ladder.nextLevel(currentLevel).orElseThrow();
        Optional<UUID> nextOwner = ladder.ownerAt(issue, nextLevel);
        if (nextOwner.isEmpty()) {
            // An unstaffed rung -- no ward officer configured, say. The level
            // still advances: stalling here would mean an issue silently stops
            // escalating because of a configuration gap, which is precisely the
            // failure mode DD-005 exists to prevent. The event records a null
            // owner, so the gap is visible rather than inferred, and the
            // existing assignee is kept rather than being cleared.
            log.warn("Escalation level {} for issue {} resolves to nobody ({} is unstaffed)",
                    nextLevel, issueId, ladder.resolverFor(nextLevel).describe());
        }

        long breachedBy = slaService.breachedBySeconds(issue, now);
        escalationRepo.saveAndFlush(EscalationEvent.of(
                issueId, nextLevel, issue.getAssignedTo(), nextOwner.orElse(null),
                EscalationReason.SLA_BREACH, breachedBy, now));

        Category category = categoryRepo.findById(issue.getCategoryCode()).orElseThrow();
        Instant rearmed = slaService.rearmedDeadline(
                category, issue.getPriority(), nextLevel, now);

        int updated = issueRepo.advanceEscalation(issueId, currentLevel, nextLevel,
                nextOwner.orElse(issue.getAssignedTo()), rearmed, now);
        if (updated == 0) {
            // Somebody advanced the level between our lock and our update. The
            // row lock makes this all but impossible; treating it as an error
            // rather than ignoring it is what keeps that assumption honest.
            throw new ConcurrentEscalationException(issueId);
        }

        log.info("Issue {} escalated to level {} ({}), breached by {}s, re-armed for {}",
                issueId, nextLevel, ladder.resolverFor(nextLevel).describe(), breachedBy, rearmed);
        return EscalationOutcome.ESCALATED;
    }

    /** The terminal rung's population: still late, nowhere left to go. */
    @Transactional(readOnly = true)
    public java.util.List<UUID> chronicBreaches(Instant now) {
        return issueRepo.findChronicBreachIds(now, props.maxEscalationLevel());
    }
}
