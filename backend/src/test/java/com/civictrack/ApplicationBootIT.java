package com.civictrack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The phase-1 gate: the application context refreshes against real PostGIS,
 * the health probe answers, and the two configuration settings that would
 * otherwise fail silently are asserted rather than assumed.
 */
@AutoConfigureMockMvc
class ApplicationBootIT extends IntegrationTestBase {

    @Autowired
    private MockMvc mvc;

    // Read straight from the environment rather than from a constant, so the
    // test fails if the YAML changes, which is the entire point.
    @Value("${spring.jpa.open-in-view}")
    private boolean openInView;

    @Value("${spring.jpa.hibernate.ddl-auto}")
    private String ddlAuto;

    @Test
    @DisplayName("the health endpoint is public and reports UP")
    void healthEndpointIsPublicAndUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("DD-009: open-in-view is off")
    void openInViewIsDisabled() {
        // Defaults to true, holding a connection for the whole request. With
        // Hikari at 8, the phase-2 concurrency test would exhaust the pool and
        // present as a phantom locking bug in the clustering engine. This
        // assertion is here so that regression is caught in CI, in a test
        // whose name says what went wrong, rather than during a debugging
        // session in week 5.
        assertThat(openInView).isFalse();
    }

    @Test
    @DisplayName("Hibernate validates the schema and never generates it")
    void hibernateOnlyValidates() {
        // Flyway owns the schema because Hibernate cannot express the
        // composite btree_gist index over a cast expression. 'validate' also
        // means this whole test class fails to start if an entity and a
        // migration have drifted apart.
        assertThat(ddlAuto).isEqualTo("validate");
    }

    @Test
    @DisplayName("an endpoint requiring authentication is not reachable anonymously")
    void protectedEndpointsAreClosedByDefault() throws Exception {
        mvc.perform(get("/api/v1/issues"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("phase 1 has no authentication mechanism yet, so Spring Security "
                            + "denies rather than challenging; phase 5 turns this into a 401 "
                            + "when the resource server supplies an entry point")
                        .isIn(401, 403));
    }
}
