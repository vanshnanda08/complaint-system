package com.civictrack.report;

import com.civictrack.IntegrationTestBase;
import com.civictrack.clustering.GeoFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The ingest endpoint end to end, including its RFC 7807 failure responses. */
@AutoConfigureMockMvc
class ReportIngestApiIT extends IntegrationTestBase {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearIssues() {
        jdbc.update("DELETE FROM issue_status_history");
        jdbc.update("DELETE FROM reports");
        jdbc.update("DELETE FROM issues");
    }

    @Test
    @DisplayName("a first report is created and told it is the first")
    void firstReportCreatesAnIssue() throws Exception {
        mvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body(GeoFixtures.LAT, GeoFixtures.LNG, 8.0))))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.clusterDecision").value("NEW_ISSUE"))
                .andExpect(jsonPath("$.reportCount").value(1))
                .andExpect(jsonPath("$.publicRef").exists())
                .andExpect(jsonPath("$.message").value(
                        "Recorded as a new issue. Yours is the first report for this problem."));
    }

    @Test
    @DisplayName("a merging report is told honestly that it strengthened an existing case")
    void mergedReportReportsItsCount() throws Exception {
        mvc.perform(post("/api/v1/reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body(GeoFixtures.LAT, GeoFixtures.LNG, 8.0))));

        // The citizen seeing their report become the second piece of evidence
        // on an existing ticket, with the distance, is the product working. A
        // silent merge is indistinguishable from a report going nowhere.
        mvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                body(GeoFixtures.latOffsetM(12), GeoFixtures.LNG, 8.0))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clusterDecision").value("MERGED"))
                .andExpect(jsonPath("$.reportCount").value(2))
                .andExpect(jsonPath("$.distanceToClusterM").exists())
                .andExpect(jsonPath("$.message").value(
                        "This is the 2nd report for this issue. Merged with 1 existing report."));
    }

    @Test
    @DisplayName("a low-accuracy report gets a problem document carrying the threshold and a remedy")
    void lowAccuracyReturnsProblemDetail() throws Exception {
        mvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body(GeoFixtures.LAT, GeoFixtures.LNG, 400.0))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.type").value(
                        "https://civictrack.example/problems/gps-accuracy-too-low"))
                .andExpect(jsonPath("$.title").value("GPS accuracy too low"))
                // The client needs these to say how far off the fix was and
                // offer manual pin placement, rather than showing a generic
                // error and losing the report.
                .andExpect(jsonPath("$.accuracyM").value(400.0))
                .andExpect(jsonPath("$.maxAccuracyM").value(150.0))
                .andExpect(jsonPath("$.remedy").exists());
    }

    @Test
    @DisplayName("a report outside the service area is rejected as such")
    void outsideServiceAreaReturnsProblemDetail() throws Exception {
        mvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body(28.6139, 77.2090, 8.0))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(
                        "https://civictrack.example/problems/outside-service-area"));
    }

    @Test
    @DisplayName("a malformed request is rejected by validation before reaching the service")
    void validationFailureReturnsProblemDetail() throws Exception {
        Map<String, Object> bad = body(GeoFixtures.LAT, GeoFixtures.LNG, 8.0);
        bad.remove("categoryCode");
        bad.put("lat", 200.0);

        mvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(
                        "https://civictrack.example/problems/validation-failed"));
    }

    private Map<String, Object> body(double lat, double lng, double accuracyM) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("categoryCode", "POTHOLE");
        m.put("lat", lat);
        m.put("lng", lng);
        m.put("accuracyM", accuracyM);
        m.put("manualPin", false);
        m.put("description", "Deep pothole near the bus stop");
        m.put("addressText", "Test Road");
        m.put("photoUrl", "https://example.test/photo.jpg");
        m.put("deviceId", "device-" + java.util.UUID.randomUUID());
        return m;
    }
}
