package com.civictrack.dashboard;

import com.civictrack.IntegrationTestBase;
import com.civictrack.issue.Issue;
import com.civictrack.support.Fixtures;
import com.civictrack.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The phase 7 aggregates, each against a corpus small enough that the right
 * answer can be worked out by hand and written into the assertion.
 *
 * <p>Issues are written as rows rather than walked through the state machine:
 * these tests are about how the figures are defined, and a walk would make
 * every resolution time a function of how fast the test ran.
 */
@AutoConfigureMockMvc
class DashboardMetricsIT extends IntegrationTestBase {

    /** 11:30 IST on 10 March. */
    private static final Instant NOW = Instant.parse("2026-03-10T06:00:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private DashboardQueries queries;
    @Autowired private Fixtures fixtures;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        fixtures.clearIssues();
        clock.set(NOW);
    }

    @Test
    @DisplayName("the median is withheld below five resolved issues, and exact at five")
    void medianNeedsAMinimumSample() {
        for (int hours : new int[]{10, 20, 30, 40}) {
            resolvedIssue(hours, true);
        }
        assertThat(queries.overallMedian().medianHours()).isNull();
        assertThat(queries.overallMedian().resolved()).isEqualTo(4);

        resolvedIssue(50, true);
        assertThat(queries.overallMedian().medianHours()).isEqualTo(30.0);

        DashboardMetricsDto.Median roads = queries.medianByDepartment().stream()
                .filter(m -> m.name().startsWith("Roads")).findFirst().orElseThrow();
        assertThat(roads.medianHours()).isEqualTo(30.0);
        // Every department is listed, with no figure, rather than left out.
        assertThat(queries.medianByDepartment()).anySatisfy(m -> {
            assertThat(m.resolved()).isZero();
            assertThat(m.medianHours()).isNull();
        });
    }

    @Test
    @DisplayName("on time is judged at the claim: citizens taking days to answer is not the department's lateness")
    void complianceJudgedAtTheClaim() {
        // Claimed an hour before the deadline, resolved by citizens two days after it.
        Issue onTime = resolvedIssue(100, true);
        jdbc.update("UPDATE issues SET clock_paused_at = due_at - interval '1 hour', "
                    + "resolved_at = due_at + interval '2 days' WHERE id = ?", onTime.getId());
        // Claimed after the deadline.
        Issue late = resolvedIssue(100, true);
        jdbc.update("UPDATE issues SET clock_paused_at = due_at + interval '1 hour' WHERE id = ?", late.getId());
        // A rejection is not a resolution and is not in either count.
        Issue rejected = resolvedIssue(10, true);
        jdbc.update("UPDATE issues SET status = 'REJECTED' WHERE id = ?", rejected.getId());

        DashboardMetricsDto.SlaCompliance c = queries.slaCompliance(NOW);
        assertThat(c.resolved()).isEqualTo(2);
        assertThat(c.onTime()).isEqualTo(1);
        assertThat(c.rate()).as("withheld below the minimum sample").isNull();
        assertThat(c.trend()).hasSize(30);
        assertThat(c.trend().get(29).day()).isEqualTo(LocalDate.of(2026, 3, 10));
    }

    @Test
    @DisplayName("a day is a Ludhiana day: 01:30 IST on the 2nd is not the 1st")
    void daysAreLocal() {
        // 20:00 UTC on 1 March is 01:30 IST on 2 March.
        fixtures.issue("POTHOLE", Instant.parse("2026-03-01T20:00:00Z"), NOW.plus(Duration.ofDays(3)));

        List<DashboardMetricsDto.DayCount> series = queries.reportedVsResolved(NOW);
        assertThat(series).hasSize(90);
        assertThat(series.get(89).day()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(count(series, LocalDate.of(2026, 3, 2))).isEqualTo(1);
        assertThat(count(series, LocalDate.of(2026, 3, 1))).isZero();
        // Empty days are zero rows, not missing ones.
        assertThat(series).allSatisfy(d -> assertThat(d.reported()).isBetween(0L, 1L));
    }

    @Test
    @DisplayName("backlog age counts open issues only, into half-open day buckets")
    void backlogAge() {
        fixtures.issue("POTHOLE", NOW.minus(Duration.ofHours(12)), NOW.plusSeconds(3600));
        fixtures.issue("POTHOLE", NOW.minus(Duration.ofDays(3)), NOW.plusSeconds(3600)); // exactly 3: the 3–7 bucket
        fixtures.issue("POTHOLE", NOW.minus(Duration.ofDays(40)), NOW.plusSeconds(3600));
        resolvedIssue(10, true);

        List<DashboardMetricsDto.AgeBucket> b = queries.backlogAge(NOW);
        assertThat(b).extracting(DashboardMetricsDto.AgeBucket::open).containsExactly(1L, 0L, 1L, 0L, 0L, 1L);
    }

    @Test
    @DisplayName("the breaching list agrees with the overdue count, most overdue first, and skips paused clocks")
    void breachingAgreesWithTheTile() throws Exception {
        Issue slightly = fixtures.issue("POTHOLE", NOW.minus(Duration.ofDays(5)), NOW.minus(Duration.ofHours(1)));
        Issue badly = fixtures.issue("POTHOLE", NOW.minus(Duration.ofDays(5)), NOW.minus(Duration.ofDays(2)));
        Issue paused = fixtures.issue("POTHOLE", NOW.minus(Duration.ofDays(5)), NOW.minus(Duration.ofDays(3)));
        jdbc.update("UPDATE issues SET status = 'PENDING_VERIFICATION' WHERE id = ?", paused.getId());
        fixtures.issue("POTHOLE", NOW, NOW.plus(Duration.ofDays(1))); // not due

        List<BreachingIssueDto> list = queries.breaching(NOW);
        assertThat(list).extracting(BreachingIssueDto::id).containsExactly(badly.getId(), slightly.getId());
        assertThat(list.get(1).overdueSeconds()).isEqualTo(3600);

        mvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(jsonPath("$.overdueCount").value(list.size()));
    }

    @Test
    @DisplayName("the metrics endpoint is public and returns every section")
    void endpoint() throws Exception {
        resolvedIssue(10, true);
        mvc.perform(get("/api/v1/dashboard/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionTime.overall.resolved").value(1))
                .andExpect(jsonPath("$.slaCompliance.trend.length()").value(30))
                .andExpect(jsonPath("$.backlogAge.length()").value(6))
                .andExpect(jsonPath("$.reportedVsResolved.length()").value(90))
                .andExpect(jsonPath("$.topClusters").isArray())
                .andExpect(jsonPath("$.minimumSample").value(5));
        mvc.perform(get("/api/v1/dashboard/breaching")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------

    /** A RESOLVED pothole that took {@code hours} from first report, resolved a day ago. */
    private Issue resolvedIssue(int hours, boolean onTime) {
        Instant resolvedAt = NOW.minus(Duration.ofDays(1));
        Instant first = resolvedAt.minus(Duration.ofHours(hours));
        Issue issue = fixtures.issue("POTHOLE", first, onTime ? resolvedAt.plusSeconds(3600) : first);
        jdbc.update("UPDATE issues SET status = 'RESOLVED', resolved_at = ?, clock_paused_at = ? WHERE id = ?",
                Timestamp.from(resolvedAt), Timestamp.from(resolvedAt), issue.getId());
        return issue;
    }

    private static long count(List<DashboardMetricsDto.DayCount> series, LocalDate day) {
        return series.stream().filter(d -> d.day().equals(day)).mapToLong(DashboardMetricsDto.DayCount::reported)
                .findFirst().orElseThrow();
    }
}
