package com.civictrack.issue;

import com.civictrack.IntegrationTestBase;
import com.civictrack.support.Fixtures;
import com.civictrack.support.MutableClock;
import com.civictrack.user.AppUser;
import com.civictrack.user.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two tabs blueprint 3.14 specifies, and the columns the row needs.
 *
 * <p>The tab narrows the caller's own scope and can never widen it, so the
 * interesting assertion is not that {@code tab=mine} shows my work but that no
 * value of {@code tab} shows me somebody else's.
 */
@AutoConfigureMockMvc
class StaffQueueTabIT extends IntegrationTestBase {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private Fixtures fixtures;
    @Autowired private MutableClock clock;

    private AppUser supervisor;
    private AppUser mine;
    private AppUser theirs;

    @BeforeEach
    void setUp() {
        fixtures.clearIssues();
        var roads = fixtures.departmentId("ROADS");
        supervisor = fixtures.user(Role.SUPERVISOR, roads, null);
        mine = fixtures.user(Role.STAFF, roads, null);
        theirs = fixtures.user(Role.STAFF, roads, null);
    }

    @Test
    @DisplayName("tab=mine shows my assignments and excludes another crew member's")
    void mineExcludesSomebodyElsesWork() throws Exception {
        Instant now = clock.instant();
        Issue forMe = fixtures.issue("POTHOLE", now, now.plusSeconds(48 * 3600));
        Issue forThem = fixtures.issue("POTHOLE", now, now.plusSeconds(48 * 3600));
        assign(forMe, mine);
        assign(forThem, theirs);

        mvc.perform(get("/api/v1/staff/queue").param("tab", "mine")
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(mine)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].publicRef").value(forMe.getPublicRef()));

        mvc.perform(get("/api/v1/staff/queue").param("tab", "mine")
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(theirs)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].publicRef").value(forThem.getPublicRef()));
    }

    @Test
    @DisplayName("tab=unassigned shows only work nobody has picked up")
    void unassignedShowsOnlyUnclaimedWork() throws Exception {
        Instant now = clock.instant();
        Issue claimed = fixtures.issue("POTHOLE", now, now.plusSeconds(48 * 3600));
        Issue unclaimed = fixtures.issue("POTHOLE", now, now.plusSeconds(48 * 3600));
        assign(claimed, mine);

        mvc.perform(get("/api/v1/staff/queue").param("tab", "unassigned")
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(mine)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].publicRef").value(unclaimed.getPublicRef()));
    }

    @Test
    @DisplayName("an unrecognised tab falls back to the whole scope rather than failing")
    void unknownTabFallsBackToAll() throws Exception {
        Instant now = clock.instant();
        fixtures.issue("POTHOLE", now, now.plusSeconds(48 * 3600));

        mvc.perform(get("/api/v1/staff/queue").param("tab", "mien")
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(mine)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("a queue row carries the category, ward and landmark the worker reads")
    void rowCarriesTheDisplayColumns() throws Exception {
        submitReport();

        mvc.perform(get("/api/v1/staff/queue")
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(mine)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].categoryName").exists())
                .andExpect(jsonPath("$[0].wardName").exists())
                .andExpect(jsonPath("$[0].landmark").value("Near the bus stop"))
                // dueAt + pausedSeconds, computed once on the server so a
                // countdown component is not a second definition of the clock.
                .andExpect(jsonPath("$[0].effectiveDeadline").exists());
    }

    private void assign(Issue issue, AppUser assignee) throws Exception {
        mvc.perform(post("/api/v1/issues/{id}/acknowledge", issue.getId())
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Acknowledged for assignment\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/issues/{id}/assign", issue.getId())
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "assigneeId", assignee.getId().toString(),
                                "note", "Assigned to a crew member"))))
                .andExpect(status().isOk());
    }

    private void submitReport() throws Exception {
        mvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "categoryCode", "POTHOLE",
                                "lat", com.civictrack.clustering.GeoFixtures.LAT,
                                "lng", com.civictrack.clustering.GeoFixtures.LNG,
                                "accuracyM", 8.0,
                                "manualPin", false,
                                "landmark", "Near the bus stop",
                                "photoUrl", "https://example.test/photo.jpg",
                                "deviceId", "device-queue-test"))))
                .andExpect(status().isCreated());
    }
}
