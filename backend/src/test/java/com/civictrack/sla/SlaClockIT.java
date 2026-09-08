package com.civictrack.sla;

import com.civictrack.IntegrationTestBase;
import com.civictrack.category.Category;
import com.civictrack.category.CategoryRepository;
import com.civictrack.issue.Issue;
import com.civictrack.issue.IssueLifecycleService;
import com.civictrack.issue.IssueRepository;
import com.civictrack.issue.IssueStatus;
import com.civictrack.issue.IssueStatusService;
import com.civictrack.issue.policy.TransitionContext;
import com.civictrack.issue.policy.VerificationTally;
import com.civictrack.support.Fixtures;
import com.civictrack.support.MutableClock;
import com.civictrack.user.AppUser;
import com.civictrack.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SLA clock pauses in PENDING_VERIFICATION.
 *
 * <p>The rule exists because the department is waiting on citizens at that
 * point and cannot make them vote. Without the pause, a crew that fixed a
 * pothole in two hours would still breach if nobody looked at the photo for
 * three days -- and the department would learn, correctly, that submitting a
 * fix for verification is worse for its numbers than leaving the ticket open.
 *
 * <p>Every assertion here is on simulated time. Testing this against the wall
 * clock would mean sleeping for hours, which is exactly what the injected
 * {@link java.time.Clock} exists to avoid.
 */
class SlaClockIT extends IntegrationTestBase {

    @Autowired private IssueLifecycleService lifecycle;
    @Autowired private IssueStatusService statusService;
    @Autowired private IssueRepository issues;
    @Autowired private CategoryRepository categories;
    @Autowired private SlaService slaService;
    @Autowired private SlaSweepService sweepService;
    @Autowired private Fixtures fixtures;
    @Autowired private MutableClock clock;

    private Instant start;
    private AppUser crew;
    private AppUser supervisor;

    @BeforeEach
    void setUp() {
        fixtures.clearIssues();
        start = Instant.parse("2026-03-01T09:00:00Z");
        clock.set(start);
        UUID roads = fixtures.departmentId("ROADS");
        crew = fixtures.user(Role.STAFF, roads, null);
        supervisor = fixtures.user(Role.SUPERVISOR, roads, null);
    }

    @AfterEach
    void restoreClock() {
        clock.reset();
    }

    @Test
    @DisplayName("time spent in PENDING_VERIFICATION is credited back to the deadline")
    void theClockDoesNotAdvanceDuringPendingVerification() {
        Issue issue = pendingVerificationIssue();
        Instant deadlineBefore = slaService.effectiveDeadline(issue);

        // Ten days of citizens not voting.
        clock.advance(Duration.ofDays(10));

        // Citizens reject, so the system reopens and the clock restarts. Note
        // that this is a SYSTEM transition: there is no staff or supervisor
        // path out of PENDING_VERIFICATION, because leaving verification is the
        // citizens' decision and not the department's.
        rejectedByCitizens(issue);
        Issue resumed = issues.findById(issue.getId()).orElseThrow();

        assertThat(resumed.getPausedSeconds())
                .as("the whole ten days should have been credited, not just part of it")
                .isCloseTo(Duration.ofDays(10).toSeconds(), org.assertj.core.data.Offset.offset(5L));
        assertThat(slaService.effectiveDeadline(resumed))
                .as("the effective deadline moves out by exactly the time spent waiting")
                .isAfter(deadlineBefore.plus(Duration.ofDays(9)));
        assertThat(resumed.getClockPausedAt())
                .as("the pause must be closed out, or the credit would be counted twice")
                .isNull();
    }

    @Test
    @DisplayName("an issue waiting on citizens is never selected as breached")
    void aPausedIssueIsNotBreached() {
        Issue issue = pendingVerificationIssue();

        clock.advance(Duration.ofDays(30));
        Instant now = clock.instant();

        assertThat(slaService.isBreached(issues.findById(issue.getId()).orElseThrow(), now))
                .as("PENDING_VERIFICATION does not run the clock, so it cannot breach")
                .isFalse();

        sweepService.sweep();

        assertThat(issues.findById(issue.getId()).orElseThrow().getEscalationLevel())
                .as("escalating a department for citizens' silence would punish the fix")
                .isZero();
    }

    @Test
    @DisplayName("the same issue does breach once work resumes and the credit is spent")
    void theCreditIsFiniteRatherThanImmunity() {
        Issue issue = pendingVerificationIssue();
        clock.advance(Duration.ofDays(10));
        rejectedByCitizens(issue);

        // Reopening is itself an escalation: a fix that did not hold is
        // evidence the current owner needs help. That accounts for level 1
        // before any deadline is missed.
        assertThat(issues.findById(issue.getId()).orElseThrow().getEscalationLevel()).isEqualTo(1);

        // The pause bought ten days. Twenty more spend it and then some.
        clock.advance(Duration.ofDays(20));
        sweepService.sweep();

        assertThat(issues.findById(issue.getId()).orElseThrow().getEscalationLevel())
                .as("a paused clock is a credit, not an exemption: the breach still lands")
                .isEqualTo(2);
    }

    /**
     * The SYSTEM transition out of PENDING_VERIFICATION when citizens reject a
     * fix. Driven directly rather than through a voting endpoint, which is a
     * later phase; the guard it has to satisfy is the real one.
     */
    private void rejectedByCitizens(Issue issue) {
        Issue current = issues.findById(issue.getId()).orElseThrow();
        statusService.systemTransition(current, IssueStatus.REOPENED,
                TransitionContext.at(clock.instant())
                        .tally(new VerificationTally(1, 0, 1, false))
                        .note("Citizens reported that the problem is not fixed")
                        .build());
        issues.saveAndFlush(current);
    }

    /**
     * Drives a real issue through acknowledge, assign, start and submit, rather
     * than writing PENDING_VERIFICATION into the row. The status setter is
     * package-private for exactly this reason: a fixture that could shortcut it
     * would let the test set up a state the state machine cannot reach.
     */
    private Issue pendingVerificationIssue() {
        Category pothole = categories.findById("POTHOLE").orElseThrow();
        Issue issue = fixtures.issue("POTHOLE", start,
                start.plusSeconds(pothole.getDefaultSlaHours() * 3600L));

        lifecycle.acknowledge(issue.getId(), supervisor.toActor(), "Seen by the roads desk");
        lifecycle.assign(issue.getId(), supervisor.toActor(), crew.getId(), "Assigned to crew");
        lifecycle.start(issue.getId(), crew.toActor(), "Crew on site");
        lifecycle.submitForVerification(issue.getId(), crew.toActor(),
                "https://example.test/after.jpg",
                "Pothole filled and compacted, surface levelled with the kerb");

        Issue submitted = issues.findById(issue.getId()).orElseThrow();
        assertThat(submitted.getStatus()).isEqualTo(IssueStatus.PENDING_VERIFICATION);
        return submitted;
    }
}
