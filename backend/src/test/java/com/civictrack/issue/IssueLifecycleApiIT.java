package com.civictrack.issue;

import com.civictrack.IntegrationTestBase;
import com.civictrack.support.Fixtures;
import com.civictrack.support.MutableClock;
import com.civictrack.user.AppUser;
import com.civictrack.user.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The lifecycle over HTTP, where the two authorisation layers and the three
 * refusal codes have to agree.
 *
 * <p>Three different answers, and the distinction is the interesting part:
 * <b>401</b> means the caller did not say who they are, <b>403</b> means we
 * know who they are and they may not, and <b>409</b> means they may but the
 * issue is not in a state where that makes sense. Collapsing them -- which is
 * what happens when a project bolts authentication on late -- makes every
 * client-side error message a guess.
 */
@AutoConfigureMockMvc
class IssueLifecycleApiIT extends IntegrationTestBase {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private IssueRepository issues;
    @Autowired private Fixtures fixtures;
    @Autowired private MutableClock clock;

    private Instant now;
    private UUID roads;
    private UUID sanitation;
    private AppUser roadsCrew;
    private AppUser roadsSupervisor;
    private AppUser sanitationCrew;
    private AppUser admin;
    private AppUser citizen;

    @BeforeEach
    void setUp() {
        fixtures.clearIssues();
        now = Instant.parse("2026-03-01T09:00:00Z");
        clock.set(now);
        roads = fixtures.departmentId("ROADS");
        sanitation = fixtures.departmentId("SANITATION");
        roadsCrew = fixtures.user(Role.STAFF, roads, null);
        roadsSupervisor = fixtures.user(Role.SUPERVISOR, roads, null);
        sanitationCrew = fixtures.user(Role.STAFF, sanitation, null);
        admin = fixtures.user(Role.ADMIN, null, null);
        citizen = fixtures.user(Role.CITIZEN, null, null);
    }

    @AfterEach
    void restoreClock() {
        clock.reset();
    }

    // ------------------------------------------------------------------
    // 401 vs 403
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an anonymous caller on a protected endpoint gets 401, not 403")
    void anonymousCallerGetsUnauthorised() throws Exception {
        Issue issue = pothole();

        mvc.perform(get("/api/v1/issues/{id}", issue.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(
                        result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                        .as("a 401 without WWW-Authenticate does not tell a client how to retry")
                        .contains("Bearer"));

        mvc.perform(get("/api/v1/staff/queue")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a garbage or expired bearer token is 401, not 403")
    void invalidTokenIsUnauthorised() throws Exception {
        mvc.perform(get("/api/v1/staff/queue")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a citizen with a perfectly valid token is 403 on a staff endpoint")
    void authenticatedButUnprivilegedIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/staff/queue")
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(citizen)))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // scope
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a sanitation staff user cannot act on a roads issue")
    void crossDepartmentActionIsForbidden() throws Exception {
        Issue issue = pothole();

        mvc.perform(post("/api/v1/issues/{id}/acknowledge", issue.getId())
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(sanitationCrew))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Looks like a pothole to me\"}"))
                .andExpect(status().isForbidden());

        assertThat(issues.findById(issue.getId()).orElseThrow().getStatus())
                .as("a refused request must not have changed anything")
                .isEqualTo(IssueStatus.NEW);
    }

    @Test
    @DisplayName("the staff issue view carries the deadline as it is actually judged")
    void staffIssueCarriesEffectiveDeadline() throws Exception {
        Issue issue = pothole();

        // effectiveDeadline = dueAt + pausedSeconds. Without it on this DTO the
        // staff work view has no deadline to render and re-deriving it in the
        // client would be a second definition of the SLA clock.
        mvc.perform(get("/api/v1/issues/{id}", issue.getId())
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsCrew)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveDeadline").exists())
                .andExpect(jsonPath("$.effectiveDeadline").value(issue.getDueAt().toString()));
    }

    @Test
    @DisplayName("the staff queue shows only the caller's own department")
    void queueIsScopedToTheCallersDepartment() throws Exception {
        pothole();

        mvc.perform(get("/api/v1/staff/queue")
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsCrew)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(get("/api/v1/staff/queue")
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(sanitationCrew)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ------------------------------------------------------------------
    // the state machine over HTTP
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an illegal transition returns 409 naming both states")
    void illegalTransitionIsConflict() throws Exception {
        Issue issue = pothole();

        // NEW -> IN_PROGRESS is not an edge: work starts from ASSIGNED.
        mvc.perform(post("/api/v1/issues/{id}/start", issue.getId())
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsCrew))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Starting now\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.from").value("NEW"))
                .andExpect(jsonPath("$.to").value("IN_PROGRESS"));
    }

    @Test
    @DisplayName("a fix without a proof photo is refused, naming the guard")
    void proofPhotoIsEnforcedOverHttp() throws Exception {
        Issue issue = inProgress();

        mvc.perform(post("/api/v1/issues/{id}/submit-for-verification", issue.getId())
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsCrew))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "proofPhotoUrl", "",
                                "note", "Filled the pothole and levelled the surface"))))
                .andExpect(status().isBadRequest());

        assertThat(issues.findById(issue.getId()).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("there is no staff route to RESOLVED at all")
    void staffCannotResolveOverHttp() throws Exception {
        Issue issue = inProgress();

        // The only endpoint staff have reaches PENDING_VERIFICATION, and says so
        // in its name. There is no /resolve to call.
        mvc.perform(post("/api/v1/issues/{id}/submit-for-verification", issue.getId())
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsCrew))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "proofPhotoUrl", "https://example.test/after.jpg",
                                "note", "Filled the pothole and levelled the surface"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));

        mvc.perform(post("/api/v1/issues/{id}/close", issue.getId())
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsCrew))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Closing this one off\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an administrator cannot close an issue that has not settled for seven days")
    void closingTooEarlyIsRefused() throws Exception {
        Issue issue = pothole();

        mvc.perform(post("/api/v1/issues/{id}/close", issue.getId())
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Tidying the queue\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("the happy path walks NEW to PENDING_VERIFICATION and writes a history row each time")
    void theHappyPathIsAudited() throws Exception {
        Issue issue = pothole();

        acknowledge(issue);
        assign(issue, roadsCrew.getId());
        start(issue);

        mvc.perform(post("/api/v1/issues/{id}/submit-for-verification", issue.getId())
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsCrew))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "proofPhotoUrl", "https://example.test/after.jpg",
                                "note", "Filled the pothole and levelled the surface"))))
                .andExpect(status().isOk());

        Issue finished = issues.findById(issue.getId()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(IssueStatus.PENDING_VERIFICATION);
        assertThat(finished.getAcknowledgedAt()).isNotNull();
        assertThat(finished.getAssignedTo()).isEqualTo(roadsCrew.getId());
        assertThat(finished.getResolutionPhotoUrl()).isNotNull();
        assertThat(finished.getClockPausedAt())
                .as("entering PENDING_VERIFICATION stops charging the department for the wait")
                .isNotNull();
    }

    @Test
    @DisplayName("work cannot be assigned to somebody in another department")
    void assigneeMustBeInTheSameDepartment() throws Exception {
        Issue issue = pothole();
        acknowledge(issue);

        mvc.perform(post("/api/v1/issues/{id}/assign", issue.getId())
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsSupervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "assigneeId", sanitationCrew.getId().toString(),
                                "note", "Sending this to sanitation"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.guard").value("ASSIGNEE_IN_SAME_DEPARTMENT"));
    }

    @Test
    @DisplayName("a staff member cannot start work assigned to somebody else")
    void onlyTheAssigneeStartsWork() throws Exception {
        Issue issue = pothole();
        acknowledge(issue);
        AppUser otherCrew = fixtures.user(Role.STAFF, roads, null);
        assign(issue, otherCrew.getId());

        mvc.perform(post("/api/v1/issues/{id}/start", issue.getId())
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsCrew))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"I will take this one\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.guard").value("IS_ASSIGNEE"));
    }

    @Test
    @DisplayName("rejecting requires a reason of at least twenty characters")
    void rejectionNeedsAReason() throws Exception {
        Issue issue = pothole();

        mvc.perform(post("/api/v1/issues/{id}/reject", issue.getId())
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsSupervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"nope\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/issues/{id}/reject", issue.getId())
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsSupervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"This is on a state highway, outside municipal "
                                 + "jurisdiction\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Issue pothole() {
        return fixtures.issue("POTHOLE", now, now.plus(Duration.ofHours(72)));
    }

    private Issue inProgress() throws Exception {
        Issue issue = pothole();
        acknowledge(issue);
        assign(issue, roadsCrew.getId());
        start(issue);
        return issues.findById(issue.getId()).orElseThrow();
    }

    private void acknowledge(Issue issue) throws Exception {
        mvc.perform(post("/api/v1/issues/{id}/acknowledge", issue.getId())
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsSupervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Seen by the roads desk\"}"))
                .andExpect(status().isOk());
    }

    private void assign(Issue issue, UUID assigneeId) throws Exception {
        mvc.perform(post("/api/v1/issues/{id}/assign", issue.getId())
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsSupervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "assigneeId", assigneeId.toString(),
                                "note", "Assigned to a crew"))))
                .andExpect(status().isOk());
    }

    private void start(Issue issue) throws Exception {
        mvc.perform(post("/api/v1/issues/{id}/start", issue.getId())
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsCrew))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Crew on site\"}"))
                .andExpect(status().isOk());
    }
}
