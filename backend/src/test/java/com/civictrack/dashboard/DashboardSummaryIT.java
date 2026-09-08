package com.civictrack.dashboard;

import com.civictrack.IntegrationTestBase;
import com.civictrack.issue.IssueRepository;
import com.civictrack.support.Fixtures;
import com.civictrack.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The four numbers the landing hero and the dashboard's live tiles read. */
@AutoConfigureMockMvc
class DashboardSummaryIT extends IntegrationTestBase {

    @Autowired private MockMvc mvc;
    @Autowired private Fixtures fixtures;
    @Autowired private MutableClock clock;
    @Autowired private IssueRepository issues;

    @BeforeEach
    void clean() {
        fixtures.clearIssues();
    }

    @Test
    @DisplayName("the summary counts overdue work and names the ward and department holding it")
    void summaryCountsOverdueWork() throws Exception {
        Instant now = clock.instant();
        fixtures.breachedIssue("POTHOLE", now);
        fixtures.breachedIssue("POTHOLE", now);
        fixtures.issue("POTHOLE", now, now.plusSeconds(48 * 3600));

        mvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdueCount").value(2))
                .andExpect(jsonPath("$.topOverdueWard.count").value(2))
                .andExpect(jsonPath("$.topOverdueDepartment.name")
                        .value("Roads and Infrastructure"));
    }

    @Test
    @DisplayName("an issue whose clock is paused is not counted as overdue")
    void pausedClockIsNotOverdue() throws Exception {
        Instant now = clock.instant();
        // paused_seconds pushes the effective deadline past now, which is what
        // "the department is not charged for waiting on citizens" means as a
        // number rather than as a sentence.
        var issue = fixtures.issue("POTHOLE", now.minusSeconds(48 * 3600),
                                   now.minusSeconds(3600));
        issues.save(issue);
        jdbcPause(issue.getId());

        mvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdueCount").value(0));
    }

    private void jdbcPause(java.util.UUID id) {
        jdbc.update("UPDATE issues SET paused_seconds = 7200 WHERE id = ?", id);
    }

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    @DisplayName("the total resolved survives the overdue query failing")
    void totalResolvedSurvivesAFailingOverdueQuery() {
        // Blueprint 3.1 makes this the landing hero's fallback. A summary that
        // 500s when one aggregate fails takes the front page of the site down
        // with it, so the two figures must not share a fate.
        IssueRepository broken = org.mockito.Mockito.mock(IssueRepository.class);
        willThrow(new org.springframework.dao.QueryTimeoutException("simulated"))
                .given(broken).countBreached(any());
        given(broken.countResolved()).willReturn(41L);
        given(broken.findTopBreachedWard(any())).willReturn(java.util.Optional.empty());
        given(broken.findTopBreachedDepartment(any())).willReturn(java.util.Optional.empty());

        DashboardSummaryDto summary = new DashboardService(broken, clock).summary();

        assertThat(summary.overdueCount())
                .as("a tile that could not be computed reports null, not zero")
                .isNull();
        assertThat(summary.totalResolved()).isEqualTo(41L);
    }
}
