package com.civictrack.me;

import com.civictrack.IntegrationTestBase;
import com.civictrack.clustering.GeoFixtures;
import com.civictrack.support.Fixtures;
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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A citizen's own reports, and nobody else's. */
@AutoConfigureMockMvc
class MyReportsIT extends IntegrationTestBase {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private Fixtures fixtures;

    private AppUser alice;
    private AppUser bob;

    @BeforeEach
    void setUp() {
        fixtures.clearIssues();
        alice = fixtures.user(Role.CITIZEN, null, null);
        bob = fixtures.user(Role.CITIZEN, null, null);
    }

    @Test
    @DisplayName("a citizen sees only their own reports, never another citizen's")
    void scopedToTheCaller() throws Exception {
        submitAs(alice, GeoFixtures.LAT, GeoFixtures.LNG);
        submitAs(bob, GeoFixtures.latOffsetM(12), GeoFixtures.LNG);

        mvc.perform(get("/api/v1/me/reports")
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].merged").value(false));

        mvc.perform(get("/api/v1/me/reports")
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                // Bob's report merged into Alice's issue: the row says so
                // plainly and still shows him the shared ticket.
                .andExpect(jsonPath("$.items[0].merged").value(true))
                .andExpect(jsonPath("$.items[0].issue.distinctReporterCount").value(2));
    }

    @Test
    @DisplayName("my reports requires a token, because there is no anonymous 'my'")
    void anonymousIsUnauthorised() throws Exception {
        mvc.perform(get("/api/v1/me/reports")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a citizen with no reports gets an empty page, not an error")
    void emptyIsAPage() throws Exception {
        mvc.perform(get("/api/v1/me/reports")
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    private void submitAs(AppUser user, double lat, double lng) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryCode", "POTHOLE");
        body.put("lat", lat);
        body.put("lng", lng);
        body.put("accuracyM", 8.0);
        body.put("manualPin", false);
        body.put("description", "Pothole at the junction");
        body.put("photoUrl", "https://example.test/photo.jpg");
        mvc.perform(post("/api/v1/reports")
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }
}
