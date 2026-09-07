package com.civictrack.user;

import com.civictrack.IntegrationTestBase;
import com.civictrack.support.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authentication, and the two things about it that are easy to get wrong.
 *
 * <p>The first is the token-type separation. Access and refresh tokens are
 * signed with the same key, so without a distinguishing claim a thirty-day
 * refresh token is a perfectly valid bearer credential for thirty days -- and
 * the fifteen-minute access lifetime, the whole reason for having two tokens,
 * becomes decorative. Nothing about that failure is visible in any normal test:
 * every endpoint keeps working.
 *
 * <p>The second is the seeded org chart. V4 creates department heads, ward
 * officers and an administrator so the escalation ladder resolves to somebody
 * on a fresh database, and it creates them with no password hash. If a null
 * hash could ever match, the migration would be shipping a set of unauthenticated
 * administrator accounts to every deployment.
 */
@AutoConfigureMockMvc
class AuthApiIT extends IntegrationTestBase {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private Fixtures fixtures;

    @Test
    @DisplayName("register, then log in, then use the access token")
    void theHappyPath() throws Exception {
        String email = "citizen-" + UUID.randomUUID() + "@civictrack.test";

        String registerBody = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email,
                                "password", "a-long-enough-password",
                                "fullName", "Test Citizen"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CITIZEN"))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(registerBody).get("accessToken").asText()).isNotBlank();

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email, "password", "a-long-enough-password"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    @DisplayName("registration always creates a CITIZEN, whatever the body says")
    void registrationCannotChooseARole() throws Exception {
        String email = "escalate-" + UUID.randomUUID() + "@civictrack.test";

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"a-long-enough-password",
                                 "fullName":"Optimist","role":"ADMIN"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CITIZEN"));

        assertThat(users.findByEmailIgnoreCase(email).orElseThrow().getRole())
                .as("a self-service endpoint that accepts a role is a privilege-escalation bug")
                .isEqualTo(Role.CITIZEN);
    }

    @Test
    @DisplayName("a wrong password and an unknown account are indistinguishable")
    void failuresDoNotEnumerateAccounts() throws Exception {
        String email = "known-" + UUID.randomUUID() + "@civictrack.test";
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email, "password", "a-long-enough-password",
                                "fullName", "Known Citizen"))))
                .andExpect(status().isCreated());

        String wrongPassword = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email, "password", "the-wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownAccount = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", "nobody-" + UUID.randomUUID() + "@civictrack.test",
                                "password", "the-wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(wrongPassword).get("detail"))
                .as("differing messages hand an attacker a free account-enumeration oracle")
                .isEqualTo(json.readTree(unknownAccount).get("detail"));
    }

    @Test
    @DisplayName("a refresh token is not accepted as a bearer credential")
    void refreshTokensCannotAuthenticateRequests() throws Exception {
        AppUser staff = fixtures.user(Role.STAFF, fixtures.departmentId("ROADS"), null);

        mvc.perform(get("/api/v1/staff/queue")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixtures.accessToken(staff)))
                .andExpect(status().isOk());

        // Both are signed with the same key; only the typ claim separates
        // them, and without this check the thirty-day token would be a
        // thirty-day access token.
        mvc.perform(get("/api/v1/staff/queue")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixtures.refreshToken(staff)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an access token is not accepted at the refresh endpoint either")
    void theTypeCheckRunsInBothDirections() throws Exception {
        AppUser staff = fixtures.user(Role.STAFF, fixtures.departmentId("ROADS"), null);

        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "refreshToken", fixtures.accessToken(staff)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("refreshing re-reads the account rather than trusting the old claims")
    void refreshReflectsTheAccountAsItIsNow() throws Exception {
        AppUser staff = fixtures.user(Role.STAFF, fixtures.departmentId("ROADS"), null);
        String refresh = fixtures.refreshToken(staff);

        staff.setRole(Role.SUPERVISOR);
        users.save(staff);

        String body = mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode node = json.readTree(body);
        assertThat(node.get("role").asText())
                .as("over thirty days a user can be promoted, moved or deactivated")
                .isEqualTo("SUPERVISOR");
    }

    @Test
    @DisplayName("the seeded org-chart accounts cannot be logged into")
    void seededAccountsHaveNoPassword() throws Exception {
        AppUser commissioner = users.findByEmailIgnoreCase("commissioner@civictrack.example")
                .orElseThrow();
        assertThat(commissioner.getPasswordHash())
                .as("V4 ships an org chart, not credentials")
                .isNull();
        assertThat(commissioner.getRole()).isEqualTo(Role.ADMIN);

        for (String attempt : new String[] {"password", "admin", "changeme", "civictrack"}) {
            mvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "email", "commissioner@civictrack.example",
                                    "password", attempt))))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("actuator metrics require ADMIN rather than being unreachable (DD-015)")
    void metricsAreReachableByAnAdministrator() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());

        AppUser citizen = fixtures.user(Role.CITIZEN, null, null);
        mvc.perform(get("/actuator/metrics")
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(citizen)))
                .andExpect(status().isForbidden());

        AppUser admin = fixtures.user(Role.ADMIN, null, null);
        mvc.perform(get("/actuator/metrics")
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(admin)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("health stays anonymous, because a load balancer cannot hold a token")
    void healthRemainsOpen() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
