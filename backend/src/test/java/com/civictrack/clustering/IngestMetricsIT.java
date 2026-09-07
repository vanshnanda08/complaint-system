package com.civictrack.clustering;

import com.civictrack.IntegrationTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the ingest timer actually records, rather than trusting that an
 * annotation is doing something.
 *
 * <p>{@code @Timed} is implemented by Micrometer's {@code TimedAspect}, and that
 * bean is what makes the annotation do anything at all. Without it the
 * annotation still compiles, the application still starts, every other test
 * still passes, and the timer silently never exists. The failure would surface
 * in week 7 as an empty metrics endpoint on the day the evaluation needs
 * p50/p95/p99 — by which point the latency numbers cannot be regenerated for
 * work already done.
 *
 * <p>The discriminating power of this test was checked rather than assumed.
 * Deleting the {@code TimedAspect} bean from {@code ObservabilityConfig} turns
 * all three cases red. Removing {@code spring-boot-starter-aop} from the POM,
 * by contrast, does <em>not</em>: aspectjweaver arrives transitively through
 * {@code spring-boot-starter-data-jpa -> spring-aspects}, so that starter is a
 * deliberate explicit declaration rather than the thing holding this up.
 *
 * <p>This test is the guard against that. It asserts on the {@link MeterRegistry}
 * directly rather than through {@code /actuator/metrics}, because that endpoint
 * is deliberately not in the security whitelist (see DD-015) and a 403 there
 * would fail this test for an unrelated reason.
 */
@AutoConfigureMockMvc
class IngestMetricsIT extends IntegrationTestBase {

    private static final String INGEST_TIMER = "civictrack.ingest";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearIssues() {
        jdbc.update("DELETE FROM issue_status_history");
        jdbc.update("DELETE FROM reports");
        jdbc.update("DELETE FROM issues");
    }

    @Test
    @DisplayName("the @Timed annotation on the ingest path records a real measurement")
    void ingestPathIsTimed() throws Exception {
        long before = ingestTimer() == null ? 0 : ingestTimer().count();

        mvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validReport())))
                .andExpect(status().isCreated());

        Timer timer = ingestTimer();

        assertThat(timer)
                .as("""
                    no '%s' timer was registered after a successful ingest. The overwhelmingly \
                    likely cause is that the TimedAspect bean in ObservabilityConfig was removed \
                    or is not being created -- @Timed is inert without it. Failing that, check \
                    that aspectjweaver is still on the classpath; it arrives transitively via \
                    spring-boot-starter-data-jpa -> spring-aspects. Related meters that DID \
                    register: %s""".formatted(
                        INGEST_TIMER, meterRegistry.getMeters().stream()
                                .map(m -> m.getId().getName())
                                .filter(n -> n.startsWith("civictrack") || n.startsWith("http.server"))
                                .distinct().sorted().toList()))
                .isNotNull();

        assertThat(timer.count())
                .as("the timer exists but recorded nothing for this request")
                .isGreaterThanOrEqualTo(before + 1);

        // A timer that exists and reports zero elapsed time is not measuring
        // the work; it is measuring a no-op wrapper.
        assertThat(timer.totalTime(TimeUnit.NANOSECONDS))
                .as("recorded duration should be non-zero")
                .isPositive();
    }

    @Test
    @DisplayName("the timer accumulates across requests, so percentiles have a population")
    void timerAccumulatesAcrossRequests() throws Exception {
        long before = ingestTimer() == null ? 0 : ingestTimer().count();

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/reports")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(validReport())))
                    .andExpect(status().isCreated());
        }

        // The week-7 evaluation reports p50/p95/p99 off this timer, which needs
        // one observation per ingest rather than one per application lifetime.
        assertThat(ingestTimer()).isNotNull();
        assertThat(ingestTimer().count()).isGreaterThanOrEqualTo(before + 3);
    }

    @Test
    @DisplayName("percentile histograms are configured, so p95 and p99 are actually available")
    void percentilesAreConfiguredForTheIngestTimer() throws Exception {
        mvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validReport())))
                .andExpect(status().isCreated());

        Timer timer = ingestTimer();
        assertThat(timer).isNotNull();

        // Configured in application.yml under management.metrics.distribution.
        // Without the histogram the timer still counts, but asking it for p95
        // returns 0 and the evaluation quietly reports zeros.
        var snapshot = timer.takeSnapshot();
        assertThat(snapshot.percentileValues())
                .as("no percentile values on the ingest timer; check "
                    + "management.metrics.distribution.percentiles in application.yml")
                .isNotEmpty();
    }

    private Timer ingestTimer() {
        return meterRegistry.find(INGEST_TIMER).timer();
    }

    private Map<String, Object> validReport() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("categoryCode", "POTHOLE");
        // A fixed, ward-interior coordinate. Repeated calls simply merge into
        // one issue, which is fine: the timer wraps the ingest path and records
        // regardless of which branch the clustering decision takes. Spreading
        // the reports out would only add a way for this test to fail for a
        // reason that has nothing to do with metrics -- an earlier draft used a
        // random offset up to 5 km and drifted outside the ward, failing with
        // 422 rather than telling us anything about the timer.
        m.put("lat", GeoFixtures.LAT);
        m.put("lng", GeoFixtures.LNG);
        m.put("accuracyM", 8.0);
        m.put("manualPin", false);
        m.put("description", "metrics probe report");
        m.put("addressText", "Test Road");
        m.put("photoUrl", "https://example.test/photo.jpg");
        m.put("deviceId", "device-" + UUID.randomUUID());
        return m;
    }
}
