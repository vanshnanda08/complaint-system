package com.civictrack.publicapi;

import com.civictrack.IntegrationTestBase;
import com.civictrack.clustering.GeoFixtures;
import com.civictrack.issue.Issue;
import com.civictrack.support.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public read surface, including the thing it must never do.
 *
 * <p>Every test here calls without a token, because "no login" is the product
 * claim and a test that authenticates would not be exercising the surface
 * anybody actually uses.
 */
@AutoConfigureMockMvc
class PublicApiIT extends IntegrationTestBase {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private Fixtures fixtures;

    @BeforeEach
    void clean() {
        fixtures.clearIssues();
    }

    // ------------------------------------------------------------------
    // the leak test
    // ------------------------------------------------------------------

    /**
     * The single most important assertion in this file.
     *
     * <p>It reads the serialised JSON rather than the record, and it walks
     * every node rather than checking the top level, because the failure it
     * guards against is a field arriving by accident -- somebody widening a
     * shared DTO, or nesting a staff record inside a public one -- rather than
     * somebody deliberately adding {@code assignedTo} to a public response.
     */
    @Test
    @DisplayName("no public issue response carries a staff, reporter or actor identity")
    void publicIssueNeverCarriesAnIdentity() throws Exception {
        Issue issue = reportedIssue();
        assignAndProgress(issue);

        for (String path : new String[]{
                "/api/v1/public/issues",
                "/api/v1/public/issues/" + issue.getId(),
                "/api/v1/public/issues/by-ref/" + issue.getPublicRef(),
                "/api/v1/public/issues/" + issue.getId() + "/reports",
                "/api/v1/public/issues/" + issue.getId() + "/history"}) {

            String body = mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(forbiddenKeysIn(json.readTree(body)))
                    .as("%s leaked an identifying field", path)
                    .isEmpty();
        }
    }

    private static java.util.List<String> forbiddenKeysIn(JsonNode node) {
        java.util.List<String> found = new java.util.ArrayList<>();
        java.util.Set<String> banned = java.util.Set.of(
                "assignedTo", "resolvedBy", "reporterId", "deviceId", "actorId",
                "photoHash", "sumW", "sumWx", "sumWy");
        node.fields().forEachRemaining(e -> {
            if (banned.contains(e.getKey())) {
                found.add(e.getKey());
            }
        });
        node.forEach(child -> found.addAll(forbiddenKeysIn(child)));
        return found;
    }

    // ------------------------------------------------------------------
    // the endpoints
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unknown ticket reference is 404, so the result screen can say so")
    void unknownReferenceIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/public/issues/by-ref/{ref}", "CT-1999-000001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No issue with reference CT-1999-000001"));
    }

    @Test
    @DisplayName("a known reference resolves to the same issue as its id")
    void referenceResolvesToTheIssue() throws Exception {
        Issue issue = reportedIssue();

        mvc.perform(get("/api/v1/public/issues/by-ref/{ref}", issue.getPublicRef()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(issue.getId().toString()))
                .andExpect(jsonPath("$.publicRef").value(issue.getPublicRef()));
    }

    @Test
    @DisplayName("the bbox query excludes issues outside the viewport and reports its cap")
    void bboxIsScopedToTheViewport() throws Exception {
        reportedIssue();

        // A viewport around the fixture.
        mvc.perform(get("/api/v1/public/issues/bbox")
                        .param("south", String.valueOf(GeoFixtures.LAT - 0.01))
                        .param("north", String.valueOf(GeoFixtures.LAT + 0.01))
                        .param("west", String.valueOf(GeoFixtures.LNG - 0.01))
                        .param("east", String.valueOf(GeoFixtures.LNG + 0.01)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.cap").value(500))
                .andExpect(jsonPath("$.capped").value(false));

        // A viewport over the Bay of Bengal.
        mvc.perform(get("/api/v1/public/issues/bbox")
                        .param("south", "14.0").param("north", "15.0")
                        .param("west", "85.0").param("east", "86.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @DisplayName("the issue index filters by status and reports the unpaged total")
    void indexFiltersAndCounts() throws Exception {
        reportedIssue();

        mvc.perform(get("/api/v1/public/issues").param("status", "NEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items.length()").value(1));

        mvc.perform(get("/api/v1/public/issues").param("status", "CLOSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @DisplayName("a blank filter is an absent filter, not a match on the empty string")
    void blankFiltersAreIgnored() throws Exception {
        reportedIssue();

        mvc.perform(get("/api/v1/public/issues").param("status", "").param("category", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("the cluster inspector gets extent, cap and positional uncertainty")
    void issueCarriesTheClusterGeometry() throws Exception {
        Issue issue = reportedIssue();

        mvc.perform(get("/api/v1/public/issues/{id}", issue.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeRadiusM").value(25))
                // POTHOLE ships at multiplier 2.00, so the dotted extent-cap
                // circle is 50 m. Reading it from the category rather than
                // hardcoding it in the client is standing rule 1 reaching the
                // frontend.
                .andExpect(jsonPath("$.clusterExtentCapM").value(50.0))
                .andExpect(jsonPath("$.positionalUncertaintyM").exists())
                .andExpect(jsonPath("$.clusterExtentM").exists());
    }

    @Test
    @DisplayName("the report list carries the clustering audit trail in submission order")
    void reportsCarryTheAuditTrail() throws Exception {
        Issue issue = reportedIssue();
        submit(GeoFixtures.latOffsetM(12), GeoFixtures.LNG, 8.0);

        mvc.perform(get("/api/v1/public/issues/{id}/reports", issue.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sequence").value(1))
                .andExpect(jsonPath("$[0].clusterDecision").value("NEW_ISSUE"))
                .andExpect(jsonPath("$[1].sequence").value(2))
                .andExpect(jsonPath("$[1].clusterDecision").value("MERGED"))
                .andExpect(jsonPath("$[1].clusterDistanceM").exists())
                .andExpect(jsonPath("$[1].effectiveRadiusM").exists());
    }

    @Test
    @DisplayName("sub-resources of an unknown issue are 404, not an empty list")
    void subResourcesOfAnUnknownIssueAreNotFound() throws Exception {
        var ghost = java.util.UUID.randomUUID();
        mvc.perform(get("/api/v1/public/issues/{id}/reports", ghost))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/issues/{id}/history", ghost))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the history shows the actor's role and never their id")
    void historyShowsRoleNotIdentity() throws Exception {
        Issue issue = reportedIssue();
        assignAndProgress(issue);

        mvc.perform(get("/api/v1/public/issues/{id}/history", issue.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actorRole").exists())
                .andExpect(jsonPath("$[0].toStatus").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$[0].actorId").doesNotExist());
    }

    @Test
    @DisplayName("categories carry both circles the cluster inspector draws")
    void categoriesCarryRadiusAndCap() throws Exception {
        mvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").exists())
                .andExpect(jsonPath("$[0].mergeRadiusM").exists())
                .andExpect(jsonPath("$[0].maxExtentMultiplier").exists())
                .andExpect(jsonPath("$[0].extentCapM").exists());
    }

    @Test
    @DisplayName("wards omit their boundaries unless asked, then serve GeoJSON")
    void wardBoundariesAreOptIn() throws Exception {
        mvc.perform(get("/api/v1/wards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").exists())
                .andExpect(jsonPath("$[0].boundary").doesNotExist());

        mvc.perform(get("/api/v1/wards").param("includeBoundary", "true"))
                .andExpect(status().isOk())
                // Raw GeoJSON, not a string that the client has to parse twice.
                .andExpect(jsonPath("$[0].boundary.type").value("MultiPolygon"))
                .andExpect(jsonPath("$[0].boundary.coordinates").isArray());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Issue reportedIssue() throws Exception {
        submit(GeoFixtures.LAT, GeoFixtures.LNG, 8.0);
        return fixtures.onlyIssue();
    }

    private void submit(double lat, double lng, double accuracy) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryCode", "POTHOLE");
        body.put("lat", lat);
        body.put("lng", lng);
        body.put("accuracyM", accuracy);
        body.put("manualPin", false);
        body.put("description", "Deep pothole across the lane");
        body.put("landmark", "Near the bus stop");
        body.put("photoUrl", "https://example.test/photo.jpg");
        body.put("deviceId", "device-" + java.util.UUID.randomUUID());
        mvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    /** Moves an issue far enough to have both a history row and an assignee. */
    private void assignAndProgress(Issue issue) throws Exception {
        var supervisor = fixtures.user(com.civictrack.user.Role.SUPERVISOR,
                fixtures.departmentId("ROADS"), null);
        var crew = fixtures.user(com.civictrack.user.Role.STAFF,
                fixtures.departmentId("ROADS"), null);

        mvc.perform(post("/api/v1/issues/{id}/acknowledge", issue.getId())
                        .header("Authorization", fixtures.bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Seen, crew going out\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/issues/{id}/assign", issue.getId())
                        .header("Authorization", fixtures.bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "assigneeId", crew.getId().toString(),
                                "note", "Assigned to the roads crew"))))
                .andExpect(status().isOk());
    }
}
