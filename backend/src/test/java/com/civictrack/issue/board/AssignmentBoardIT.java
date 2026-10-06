package com.civictrack.issue.board;

import com.civictrack.IntegrationTestBase;
import com.civictrack.issue.Issue;
import com.civictrack.issue.IssueRepository;
import com.civictrack.issue.IssueStatus;
import com.civictrack.support.Fixtures;
import com.civictrack.support.MutableClock;
import com.civictrack.user.AppUser;
import com.civictrack.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The assignment board (blueprint 3.16): reporter identities for supervisors
 * and nobody below, a picker that offers only people the transition will
 * accept, and the lifecycle moving past ACKNOWLEDGED through the existing
 * transition endpoints.
 */
@AutoConfigureMockMvc
class AssignmentBoardIT extends IntegrationTestBase {

    @Autowired private MockMvc mvc;
    @Autowired private Fixtures fixtures;
    @Autowired private MutableClock clock;
    @Autowired private IssueRepository issues;
    @Autowired private JdbcTemplate jdbc;

    private AppUser roadsHead;
    private AppUser roadsCrew;
    private AppUser waterCrew;
    private AppUser citizen;
    private Issue issue;

    @BeforeEach
    void setUp() {
        fixtures.clearIssues();
        clock.set(Instant.parse("2026-03-01T09:00:00Z"));
        UUID roads = fixtures.departmentId("ROADS");
        roadsHead = fixtures.user(Role.SUPERVISOR, roads, null);
        roadsCrew = fixtures.user(Role.STAFF, roads, null);
        waterCrew = fixtures.user(Role.STAFF, fixtures.departmentId("WATER"), null);
        citizen = fixtures.user(Role.CITIZEN, null, null);

        issue = fixtures.issue("POTHOLE", clock.instant(), clock.instant().plus(Duration.ofDays(3)));
        fixtures.report(issue.getId(), citizen.getId());
        fixtures.report(issue.getId(), null);
    }

    @Test
    @DisplayName("the board names reporters for a supervisor; the staff queue never does")
    void identitiesForSupervisorsOnly() throws Exception {
        mvc.perform(get("/api/v1/supervisor/queue").header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsHead)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].row.id").value(issue.getId().toString()))
                .andExpect(jsonPath("$[0].reporters[0].fullName").value(citizen.getFullName()))
                .andExpect(jsonPath("$[0].anonymousReporters").value(1));

        // Crew and citizens cannot reach the board at all...
        for (AppUser who : new AppUser[]{roadsCrew, citizen}) {
            mvc.perform(get("/api/v1/supervisor/queue").header(HttpHeaders.AUTHORIZATION, fixtures.bearer(who)))
                    .andExpect(status().isForbidden());
        }
        // ...and the crew's own queue carries no identity.
        mvc.perform(get("/api/v1/staff/queue").header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsCrew)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reporters").doesNotExist())
                .andExpect(jsonPath("$[0].assigneeName").doesNotExist());
    }

    @Test
    @DisplayName("the picker lists the department's own people, and a supervisor cannot list another department")
    void membersAreScoped() throws Exception {
        UUID roads = fixtures.departmentId("ROADS");
        mvc.perform(get("/api/v1/supervisor/departments/{d}/members", roads)
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsHead)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(roadsCrew.getId().toString())))
                .andExpect(jsonPath("$[*].id", not(hasItem(waterCrew.getId().toString()))));

        mvc.perform(get("/api/v1/supervisor/departments/{d}/members", fixtures.departmentId("WATER"))
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsHead)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("acknowledge, assign from the board, and the assignee can start: the lifecycle no longer stalls")
    void lifecycleMovesPastAcknowledged() throws Exception {
        act(roadsHead, "acknowledge", "{}").andExpect(status().isOk());

        // Someone outside the department is refused by the transition guard.
        act(roadsHead, "assign", "{\"assigneeId\":\"" + waterCrew.getId() + "\"}")
                .andExpect(status().is4xxClientError());

        act(roadsHead, "assign", "{\"assigneeId\":\"" + roadsCrew.getId() + "\"}").andExpect(status().isOk());
        mvc.perform(get("/api/v1/supervisor/queue").header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsHead)))
                .andExpect(jsonPath("$[0].assigneeName").value(roadsCrew.getFullName()));
        mvc.perform(get("/api/v1/supervisor/departments/{d}/members", fixtures.departmentId("ROADS"))
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsHead)))
                .andExpect(jsonPath("$[?(@.id == '" + roadsCrew.getId() + "')].openAssigned").value(hasItem(1)));

        act(roadsCrew, "start", "{}").andExpect(status().isOk());
        assertThat(issues.findById(issue.getId()).orElseThrow().getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("an escalated NEW issue still needs a crew: it is on the to-assign tab though escalation gave it a holder")
    void escalatedWorkIsStillToAssign() throws Exception {
        // What the SLA ladder does: hands assigned_to up to the commissioner
        // without any ASSIGNED transition. Found by the browser walk, where
        // every escalated issue had vanished from a null-assignee filter.
        jdbc.update("UPDATE issues SET assigned_to = ?, escalation_level = 4 WHERE id = ?",
                fixtures.adminId(), issue.getId());

        mvc.perform(get("/api/v1/supervisor/queue?tab=to_assign").header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsHead)))
                .andExpect(jsonPath("$[*].row.id", hasItem(issue.getId().toString())));
        mvc.perform(get("/api/v1/supervisor/queue?tab=unassigned").header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsHead)))
                .andExpect(jsonPath("$[*].row.id", not(hasItem(issue.getId().toString()))));
    }

    private ResultActions act(AppUser who, String verb, String body) throws Exception {
        return mvc.perform(post("/api/v1/issues/{id}/" + verb, issue.getId())
                .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(who))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
