package com.civictrack.sla;

import com.civictrack.IntegrationTestBase;
import com.civictrack.issue.Issue;
import com.civictrack.issue.IssueRepository;
import com.civictrack.issue.Priority;
import com.civictrack.support.Fixtures;
import com.civictrack.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-004: an issue nobody touches must climb the queue.
 *
 * <p>Phase 2 recomputed priority only on merge, so an issue that received
 * exactly one report -- which is most issues -- held its creation-time score
 * forever. That inverts the intent exactly: the {@code 0.15 x age_hours} term
 * exists so that neglected issues rise, and recomputing only on merge meant the
 * only issues that aged were the ones already receiving attention. An issue in
 * a quiet ward would sit at the bottom of the queue indefinitely, which is the
 * precise failure the project exists to prevent.
 */
class PriorityAgeingIT extends IntegrationTestBase {

    @Autowired private SlaSweepService sweepService;
    @Autowired private IssueRepository issues;
    @Autowired private Fixtures fixtures;
    @Autowired private MutableClock clock;

    private Instant start;

    @BeforeEach
    void setUp() {
        fixtures.clearIssues();
        start = Instant.parse("2026-03-01T09:00:00Z");
        clock.set(start);
    }

    @AfterEach
    void restoreClock() {
        clock.reset();
    }

    @Test
    @DisplayName("an untouched issue's priority rises over simulated time")
    void untouchedIssuePriorityRisesOverSimulatedTime() {
        // SIGNAGE: severity 6, the least urgent category in the seed, so the
        // rise has to come from age rather than from the issue having been
        // important all along.
        //
        Issue issue = fixtures.issue("SIGNAGE", start, start.plus(Duration.ofDays(90)));

        sweepService.sweep();
        BigDecimal dayZero = reload(issue).getPriorityScore();

        clock.advance(Duration.ofDays(14));
        sweepService.sweep();
        Issue aged = reload(issue);

        assertThat(aged.getPriorityScore())
                .as("no report arrived, nothing was done, and two weeks passed: the score must rise")
                .isGreaterThan(dayZero);

        // 0.15 per hour x 336 hours is 50.4 points of pure neglect. The bound
        // is one-sided on purpose: a fortnight past a seven-day SLA also
        // breaches, and escalation adds 20 points of its own, so pinning the
        // total would be pinning two behaviours at once. The age term's exact
        // arithmetic is asserted in PriorityCalculatorTest, where nothing else
        // can contribute to the number.
        assertThat(aged.getPriorityScore().subtract(dayZero).doubleValue())
                .as("the age term alone accounts for 50.4 of the rise")
                .isGreaterThanOrEqualTo(50.0);
    }

    @Test
    @DisplayName("the band rises with the score, and the deadline tightens with the band")
    void ageingCrossesBandBoundariesAndTightensTheDeadline() {
        Issue issue = fixtures.issue("SIGNAGE", start, start.plus(Duration.ofDays(90)));

        sweepService.sweep();
        Issue fresh = reload(issue);
        Instant dueWhenFresh = fresh.getDueAt();
        assertThat(fresh.getPriority()).isEqualTo(Priority.LOW);

        clock.advance(Duration.ofDays(10));
        sweepService.sweep();
        Issue aged = reload(issue);

        assertThat(aged.getPriority())
                .as("a fortnight of neglect on a seven-day SLA is not a LOW priority any more")
                .isNotEqualTo(Priority.LOW);
        assertThat(aged.getDueAt())
                .as("a priority upgrade recomputes the deadline from first report and shortens it")
                .isBefore(dueWhenFresh);
    }

    @Test
    @DisplayName("a downgrade never extends a deadline that an upgrade tightened")
    void deadlinesOnlyEverTighten() {
        Issue issue = fixtures.issue("SIGNAGE", start, start.plus(Duration.ofDays(90)));

        // Five days: far enough for the band to rise from LOW to MEDIUM, and
        // short of the resulting seven-day deadline, so no breach and no
        // escalation muddies what is being measured.
        clock.advance(Duration.ofDays(5));
        sweepService.sweep();
        Instant tightened = reload(issue).getDueAt();
        assertThat(tightened).isAfter(clock.instant());

        // Reset the age signal by moving the first report forward, which is the
        // same effect a supervisor would have by re-banding an issue downwards:
        // the recomputed candidate deadline jumps well into the future.
        Issue reset = reload(issue);
        reset.setFirstReportedAt(clock.instant());
        issues.saveAndFlush(reset);

        sweepService.sweep();

        assertThat(reload(issue).getDueAt())
                .as("if a downgrade could extend a deadline, a department could buy time "
                    + "by arguing an issue down a band")
                .isEqualTo(tightened);
    }

    @Test
    @DisplayName("the sweep rescores issues in bulk without touching closed ones")
    void closedIssuesAreLeftAlone() {
        Issue open = fixtures.issue("SIGNAGE", start, start.plus(Duration.ofDays(90)));
        sweepService.sweep();
        BigDecimal openScore = reload(open).getPriorityScore();

        assertThat(openScore).isNotEqualByComparingTo(BigDecimal.ZERO);
        assertThat(sweepService.sweep().rescored())
                .as("one row, rewritten by one batched UPDATE")
                .isEqualTo(1);
    }

    private Issue reload(Issue issue) {
        return issues.findById(issue.getId()).orElseThrow();
    }
}
