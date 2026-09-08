package com.civictrack.dashboard;

import com.civictrack.IntegrationTestBase;
import com.civictrack.issue.Issue;
import com.civictrack.issue.IssueRepository;
import com.civictrack.sla.SlaProperties;
import com.civictrack.sla.SlaService;
import com.civictrack.support.Fixtures;
import com.civictrack.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the dashboard's definition of "overdue" to the sweep's.
 *
 * <p>There are now three places that answer "is this issue breached": the
 * SQL in {@code lockBreachedIssueIds} and {@code findChronicBreachIds} that
 * the escalation sweep works from, {@code countBreached} that the public
 * dashboard publishes, and {@link SlaService#isBreached} in Java. If they
 * disagree, the number on the public accountability dashboard is not the
 * number the city is actually escalating on -- which is precisely the
 * discrepancy this project exists to argue against.
 *
 * <p>The test constructs issues on both sides of every boundary the predicate
 * has: the deadline, the paused-clock credit, and the escalation cap that
 * splits the sweep's two queries.
 */
class DashboardBreachAgreementIT extends IntegrationTestBase {

    @Autowired private IssueRepository issues;
    @Autowired private Fixtures fixtures;
    @Autowired private MutableClock clock;
    @Autowired private SlaService sla;
    @Autowired private SlaProperties slaProps;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        fixtures.clearIssues();
    }

    @Test
    @DisplayName("the dashboard count equals the sweep's worklist, and both equal the Java predicate")
    void allThreeDefinitionsAgree() {
        Instant now = clock.instant();

        Issue overdue = fixtures.breachedIssue("POTHOLE", now);
        Issue chronic = fixtures.breachedIssue("POTHOLE", now);
        jdbc.update("UPDATE issues SET escalation_level = ? WHERE id = ?",
                    slaProps.maxEscalationLevel(), chronic.getId());

        Issue paused = fixtures.breachedIssue("POTHOLE", now);
        jdbc.update("UPDATE issues SET paused_seconds = 86400 WHERE id = ?", paused.getId());

        Issue comfortable = fixtures.issue("POTHOLE", now, now.plusSeconds(48 * 3600));

        long dashboard = issues.countBreached(now);

        Set<UUID> sweep = new HashSet<>(
                issues.lockBreachedIssueIds(now, slaProps.maxEscalationLevel(), 500));
        sweep.addAll(issues.findChronicBreachIds(now, slaProps.maxEscalationLevel()));

        Set<UUID> javaPredicate = new HashSet<>();
        for (Issue i : issues.findAll()) {
            if (sla.isBreached(i, now)) {
                javaPredicate.add(i.getId());
            }
        }

        assertThat(sweep)
                .as("the sweep's two queries must together cover exactly the breached set")
                .containsExactlyInAnyOrder(overdue.getId(), chronic.getId());
        assertThat(dashboard)
                .as("the published number must equal what the city is escalating on")
                .isEqualTo(sweep.size());
        assertThat(javaPredicate)
                .as("SlaService.isBreached must agree with both SQL definitions")
                .isEqualTo(sweep);
        assertThat(paused.getId()).isNotIn(sweep);
        assertThat(comfortable.getId()).isNotIn(sweep);
    }
}
