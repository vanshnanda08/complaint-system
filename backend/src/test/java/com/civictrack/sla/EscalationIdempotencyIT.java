package com.civictrack.sla;

import com.civictrack.IntegrationTestBase;
import com.civictrack.issue.Issue;
import com.civictrack.issue.IssueRepository;
import com.civictrack.sla.escalation.EscalationEvent;
import com.civictrack.sla.escalation.EscalationEventRepository;
import com.civictrack.sla.escalation.EscalationOutcome;
import com.civictrack.sla.escalation.EscalationService;
import com.civictrack.support.Fixtures;
import com.civictrack.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The idempotency claim, tested at the layer each part of it actually lives in.
 *
 * <p>The claim is that running the sweep twice, three times, or concurrently on
 * two instances produces the same result as running it once, and it rests on
 * three independent mechanisms. This class deliberately does not test all three
 * through one entry point, because a test that ran the ShedLock-wrapped job
 * twice and found one event would prove only that ShedLock works -- which it
 * does, and which is the least interesting of the three.
 *
 * <ul>
 *   <li>{@link #sweepRunTwiceProducesExactlyOneEscalation()} calls the sweep
 *       directly, with no lock in front of it.</li>
 *   <li>{@link #concurrentEscalationOfOneIssueProducesOneEvent()} races the
 *       per-issue escalation, which is where the row lock, the re-check and the
 *       compare-and-swap live.</li>
 *   <li>{@code EscalationJobLockIT} races the job itself, which is ShedLock's
 *       job.</li>
 * </ul>
 */
class EscalationIdempotencyIT extends IntegrationTestBase {

    @Autowired private SlaSweepService sweepService;
    @Autowired private EscalationService escalationService;
    @Autowired private EscalationEventRepository escalations;
    @Autowired private IssueRepository issues;
    @Autowired private Fixtures fixtures;
    @Autowired private MutableClock clock;

    private Instant now;

    @BeforeEach
    void setUp() {
        fixtures.clearIssues();
        now = Instant.parse("2026-03-01T09:00:00Z");
        clock.set(now);
    }

    @AfterEach
    void restoreClock() {
        clock.reset();
    }

    @Test
    @DisplayName("running the sweep twice produces exactly one escalation event")
    void sweepRunTwiceProducesExactlyOneEscalation() {
        Issue issue = fixtures.breachedIssue("POTHOLE", now);

        sweepService.sweep();
        sweepService.sweep();

        List<EscalationEvent> events = escalations.findByIssueIdOrderByLevelAsc(issue.getId());
        assertThat(events)
                .as("a second sweep must not re-escalate an issue whose deadline it just re-armed")
                .hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(1);
        assertThat(issues.findById(issue.getId()).orElseThrow().getEscalationLevel()).isEqualTo(1);
    }

    @Test
    @DisplayName("the second sweep is a no-op because the issue is no longer breached")
    void escalationRearmsTheDeadlineRatherThanLeavingItInThePast() {
        Issue issue = fixtures.breachedIssue("POTHOLE", now);
        Instant originalDue = issue.getDueAt();

        sweepService.sweep();

        Issue after = issues.findById(issue.getId()).orElseThrow();
        assertThat(after.getDueAt())
                .as("without a re-arm the issue stays breached forever and the sweep loops on it")
                .isAfter(originalDue)
                .isAfter(now);

        // POTHOLE is a 72-hour SLA at MEDIUM, so level 1 grants half of that,
        // comfortably above the two-hour floor. See DD-016 for why the
        // specification's "remaining / 2" could not be implemented literally.
        assertThat(Duration.between(now, after.getDueAt()).toHours())
                .isBetween(35L, 37L);
    }

    @Test
    @DisplayName("ten threads escalating one issue produce one event and one level")
    void concurrentEscalationOfOneIssueProducesOneEvent() throws Exception {
        Issue issue = fixtures.breachedIssue("POTHOLE", now);
        int threads = 10;

        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger escalated = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<EscalationOutcome>> tasks = java.util.stream.IntStream.range(0, threads)
                    .<Callable<EscalationOutcome>>mapToObj(i -> () -> {
                        startGate.await(5, TimeUnit.SECONDS);
                        try {
                            EscalationOutcome outcome = escalationService.escalateOne(issue.getId(), now);
                            if (outcome == EscalationOutcome.ESCALATED) {
                                escalated.incrementAndGet();
                            }
                            return outcome;
                        } catch (RuntimeException e) {
                            // A duplicate-key or lost compare-and-swap is a
                            // successful defence, not a failure of the test.
                            return EscalationOutcome.ALREADY_RECORDED;
                        }
                    })
                    .toList();

            startGate.countDown();
            for (Future<EscalationOutcome> f : pool.invokeAll(tasks, 30, TimeUnit.SECONDS)) {
                f.get();
            }
        }

        assertThat(escalated.get())
                .as("exactly one of %d concurrent attempts may escalate", threads)
                .isEqualTo(1);
        assertThat(escalations.countByIssueId(issue.getId()))
                .as("UNIQUE (issue_id, level) and the compare-and-swap both exist to make this 1")
                .isEqualTo(1);
        assertThat(issues.findById(issue.getId()).orElseThrow().getEscalationLevel()).isEqualTo(1);
    }

    @Test
    @DisplayName("an issue that stopped being breached is not escalated, even though it was selected")
    void reCheckUnderTheLockPreventsEscalatingASettledIssue() {
        Issue issue = fixtures.breachedIssue("POTHOLE", now);

        // The batch query ran; before this issue is processed, its deadline
        // moves out. This is not hypothetical -- a supervisor raising the
        // priority, or a concurrent sweep re-arming it, does exactly that.
        issue.setDueAt(now.plus(Duration.ofHours(5)));
        issues.saveAndFlush(issue);

        assertThat(escalationService.escalateOne(issue.getId(), now))
                .as("escalating an issue that is no longer late names an owner who did nothing wrong")
                .isEqualTo(EscalationOutcome.NOT_BREACHED);
        assertThat(escalations.countByIssueId(issue.getId())).isZero();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    @DisplayName("the compare-and-swap refuses to advance from a level nobody is at")
    void compareAndSwapRefusesAStaleExpectedLevel() {
        Issue issue = fixtures.breachedIssue("POTHOLE", now);
        UUID owner = fixtures.departmentHeadId("ROADS");
        Instant newDue = now.plus(Duration.ofHours(36));

        // This layer needs its own test, and finding that out was the point of
        // trying to break the concurrent test above. Removing the
        // `AND escalation_level = :expected` predicate leaves every other
        // escalation test green: under the row lock, the unique constraint
        // already holds the outcome to one event, so the compare-and-swap never
        // gets a chance to be the thing that saves us. It is the layer that
        // matters if either of the other two is ever weakened, and without this
        // test nothing in the suite would notice it had stopped working.
        assertThat(issues.advanceEscalation(issue.getId(), 3, 4, owner, newDue, now))
                .as("the issue is at level 0; an update expecting 3 must match nothing")
                .isZero();

        assertThat(issues.advanceEscalation(issue.getId(), 0, 1, owner, newDue, now))
                .as("and the same update with the level actually held does apply")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("escalation stops at level 4 and does not overflow")
    void escalationStopsAtTheTerminalRung() {
        Issue issue = fixtures.breachedIssue("POTHOLE", now);

        // Six sweeps for a four-rung ladder: the two extra are the point.
        for (int i = 0; i < 6; i++) {
            sweepService.sweep();
            // Each escalation re-arms the deadline, so time has to pass before
            // the issue is breached again. Two days is past every re-armed
            // deadline the ladder can produce for a 72-hour SLA.
            clock.advance(Duration.ofDays(2));
            now = clock.instant();
        }

        Issue after = issues.findById(issue.getId()).orElseThrow();
        assertThat(after.getEscalationLevel())
                .as("the ladder is four rungs; level 5 resolves to nobody")
                .isEqualTo(4);
        assertThat(escalations.findByIssueIdOrderByLevelAsc(issue.getId()))
                .extracting(EscalationEvent::getLevel)
                .containsExactly(1, 2, 3, 4);
    }

    @Test
    @DisplayName("a level-4 issue that is still breached appears in the chronic-breach list")
    void terminalIssuesRemainVisible() {
        Issue issue = fixtures.breachedIssue("POTHOLE", now);
        issue.setEscalationLevel(4);
        issues.saveAndFlush(issue);

        assertThat(escalationService.escalateOne(issue.getId(), now))
                .isEqualTo(EscalationOutcome.TERMINAL);

        List<UUID> chronic = escalationService.chronicBreaches(now);
        assertThat(chronic)
                .as("capping the number must not make the issue disappear; that is the whole "
                    + "reason for capping rather than letting the level climb")
                .contains(issue.getId());
    }
}
