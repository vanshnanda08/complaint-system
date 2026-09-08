package com.civictrack.sla;

import com.civictrack.IntegrationTestBase;
import com.civictrack.issue.Issue;
import com.civictrack.sla.escalation.EscalationEventRepository;
import com.civictrack.support.Fixtures;
import com.civictrack.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ShedLock: the outermost of the three idempotency layers.
 *
 * <p>This is the layer that matters across processes rather than across
 * threads -- two application instances, or the overlap during a rolling
 * redeploy, where nothing in the JVM can coordinate them. The lock lives in the
 * {@code shedlock} table, so it works between machines that share only a
 * database.
 *
 * <p>Calling {@code job.run()} directly still goes through ShedLock: the
 * annotation is applied by an AOP proxy registered by
 * {@code @EnableSchedulerLock}, not by the scheduler. That is what makes this
 * testable without waiting for a cron tick.
 */
class EscalationJobLockIT extends IntegrationTestBase {

    @Autowired private SlaEscalationJob job;
    @Autowired private EscalationEventRepository escalations;
    @Autowired private Fixtures fixtures;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;

    private Instant now;

    @BeforeEach
    void setUp() {
        fixtures.clearIssues();
        releaseSchedulerLock();
        now = Instant.parse("2026-03-01T09:00:00Z");
        clock.set(now);
    }

    @AfterEach
    void restoreClock() {
        clock.reset();
        releaseSchedulerLock();
    }

    /**
     * Expires the lock rather than deleting the row, which matters and is not
     * obvious.
     *
     * <p>ShedLock's JDBC provider keeps an in-memory registry of the lock names
     * it has already inserted, and skips the INSERT for those on subsequent
     * calls -- it issues only the conditional UPDATE. Deleting the row leaves
     * that registry saying the row exists, so the UPDATE matches nothing, the
     * lock is never acquired, and every later job invocation in the same JVM
     * silently does nothing. That cost a confusing "the sweep escalated zero
     * issues" failure that looked like a bug in the sweep.
     */
    private void releaseSchedulerLock() {
        // An unambiguously past instant rather than an offset from now(): the
        // column is TIMESTAMPTZ and the JVM, the container and the driver do
        // not all agree on a default zone, which cost an hour of chasing a
        // "lock is free but nothing ran" failure.
        jdbc.update("UPDATE shedlock SET lock_until = TIMESTAMPTZ '2000-01-01 00:00:00+00' "
                    + "WHERE name = 'slaEscalation'");
    }

    @Test
    @DisplayName("two concurrent job executions produce exactly one escalation event")
    void twoConcurrentJobRunsProduceOneEvent() throws Exception {
        Issue issue = fixtures.breachedIssue("POTHOLE", now);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await(5, TimeUnit.SECONDS);
                        job.run();
                    } catch (Exception ignored) {
                        // A second runner being refused the lock is the
                        // expected outcome, not a failure.
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(finished.await(60, TimeUnit.SECONDS))
                    .as("both job invocations should return; a hang here means the sweep is "
                        + "waiting on a row lock it is itself holding")
                    .isTrue();
        }

        assertThat(escalations.countByIssueId(issue.getId()))
                .as("a redeploy overlap must not double-escalate every breached issue in the city")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the job takes a named lock, so a second instance can see it is held")
    void theLockIsRecordedInTheDatabase() {
        fixtures.breachedIssue("POTHOLE", now);

        job.run();

        // Locking in the database rather than in the JVM is what makes this
        // work between two instances that share nothing else.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM shedlock WHERE name = 'slaEscalation'", Integer.class))
                .isEqualTo(1);
    }
}
