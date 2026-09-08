package com.civictrack.issue;

import com.civictrack.category.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The scoring formula, isolated from everything that could otherwise account
 * for a number moving.
 *
 * <pre>
 *   score = severity_weight
 *         + 12 * log2(1 + distinct_reporters)
 *         + 0.15 * unpaused_age_hours
 *         + 20 * escalation_level
 *         + 15 * reopen_count
 * </pre>
 *
 * <p>{@code PriorityAgeingIT} proves the sweep actually applies this to
 * untouched issues; this proves the arithmetic. Splitting them matters,
 * because in the integration test an ageing issue also breaches and escalates,
 * so a total asserted there would be pinning three behaviours at once and would
 * still pass with the age term deleted.
 */
class PriorityCalculatorTest {

    private static final Instant REPORTED = Instant.parse("2026-03-01T09:00:00Z");

    private final PriorityCalculator calculator = new PriorityCalculator();

    @Test
    @DisplayName("age contributes 0.15 points per hour and nothing else changes")
    void ageTermIsExactlyFifteenHundredthsPerHour() {
        Issue issue = issue(1, 0, 0);
        Category signage = category(6);

        double atZero = calculator.score(issue, signage, REPORTED);
        double afterFortnight = calculator.score(issue, signage, REPORTED.plus(Duration.ofDays(14)));

        assertThat(afterFortnight - atZero)
                .as("14 days x 24 hours x 0.15")
                .isCloseTo(50.4, within(0.001));
    }

    @Test
    @DisplayName("paused time is excluded from the age term")
    void timeWaitingOnCitizensDoesNotAgeAnIssue() {
        Issue running = issue(1, 0, 0);
        Issue paused = issue(1, 0, 0);
        paused.setPausedSeconds(Duration.ofDays(7).toSeconds());

        Instant fortnightLater = REPORTED.plus(Duration.ofDays(14));
        double runningScore = calculator.score(running, category(6), fortnightLater);
        double pausedScore = calculator.score(paused, category(6), fortnightLater);

        // Charging paused time here while explicitly not charging it in the
        // deadline would make the two halves of the same policy disagree: the
        // department would climb the queue for time it is not accountable for.
        assertThat(runningScore - pausedScore).isCloseTo(25.2, within(0.001));
    }

    @Test
    @DisplayName("reporters count logarithmically, so one street cannot monopolise the queue")
    void reporterTermIsLogarithmic() {
        Category pothole = category(15);

        double one = calculator.score(issue(1, 0, 0), pothole, REPORTED);
        double five = calculator.score(issue(5, 0, 0), pothole, REPORTED);
        double forty = calculator.score(issue(40, 0, 0), pothole, REPORTED);
        double fortyFive = calculator.score(issue(45, 0, 0), pothole, REPORTED);

        assertThat(five - one)
                .as("1 to 5 reporters is a real signal: 12 x log2(6/2)")
                .isCloseTo(19.02, within(0.05));
        assertThat(fortyFive - forty)
                .as("40 to 45 is barely one: 12 x log2(46/41)")
                .isCloseTo(1.99, within(0.05));

        // The property, rather than the two numbers: the same increment of
        // five reporters is worth an order of magnitude less once a crowd
        // exists. Linear weighting would make them identical, and one
        // organised street could then out-report the rest of the city into
        // the bottom of the queue -- turning the priority signal into a
        // measure of coordination rather than of need.
        assertThat(five - one).isGreaterThan(8 * (fortyFive - forty));
    }

    @Test
    @DisplayName("escalation and reopening both raise the score")
    void escalationAndReopenAreWeighted() {
        Category pothole = category(15);
        double base = calculator.score(issue(1, 0, 0), pothole, REPORTED);

        assertThat(calculator.score(issue(1, 2, 0), pothole, REPORTED) - base).isCloseTo(40.0, within(0.001));
        assertThat(calculator.score(issue(1, 0, 3), pothole, REPORTED) - base).isCloseTo(45.0, within(0.001));
    }

    @Test
    @DisplayName("bands are read off the score, in one place")
    void bandsMatchTheThresholds() {
        assertThat(calculator.band(0)).isEqualTo(Priority.LOW);
        assertThat(calculator.band(24.99)).isEqualTo(Priority.LOW);
        assertThat(calculator.band(25)).isEqualTo(Priority.MEDIUM);
        assertThat(calculator.band(50)).isEqualTo(Priority.HIGH);
        assertThat(calculator.band(80)).isEqualTo(Priority.CRITICAL);
    }

    private static Issue issue(int reporters, int escalationLevel, int reopenCount) {
        Issue issue = new Issue();
        issue.setFirstReportedAt(REPORTED);
        issue.setDistinctReporterCount(reporters);
        issue.setEscalationLevel(escalationLevel);
        issue.setReopenCount(reopenCount);
        issue.setPriorityScore(BigDecimal.ZERO);
        return issue;
    }

    private static Category category(int severityWeight) {
        Category category = new Category();
        category.setSeverityWeight(severityWeight);
        return category;
    }
}
