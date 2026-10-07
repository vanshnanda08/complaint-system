package com.civictrack.seed;

import com.civictrack.IntegrationTestBase;
import com.civictrack.support.Fixtures;
import com.civictrack.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("seed")
@TestPropertySource(properties = {
        "civictrack.seed.enabled=true", "civictrack.seed.corpus-size=250",
        "civictrack.seed.random-seed=42",
        "civictrack.seed.labels-file-path=target/reproducible-labels.csv"
})
class SeedReproducibilityIT extends IntegrationTestBase {
    @Autowired SeedDataGenerator generator;
    @Autowired Fixtures fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    @Test
    void identicalSettingsReproduceLabelsAndEveryReportAndIssueDespiteWallClockChange() throws Exception {
        try {
            fixtures.clearIssues();
            clock.set(Instant.parse("2026-10-07T00:00:00Z"));
            generator.run();
            String labels = Files.readString(Path.of("target/reproducible-labels.csv"));
            String reports = fingerprint("reports");
            String issues = fingerprint("issues");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reports", Integer.class)).isEqualTo(250);
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT status) FROM issues", Integer.class)).isGreaterThan(5);

            fixtures.clearIssues();
            clock.set(Instant.parse("2027-02-15T00:00:00Z"));
            generator.run();
            assertThat(Files.readString(Path.of("target/reproducible-labels.csv"))).isEqualTo(labels);
            assertThat(fingerprint("reports")).isEqualTo(reports);
            assertThat(fingerprint("issues")).isEqualTo(issues);
            assertThat(clock.instant()).isEqualTo(Instant.parse("2027-02-15T00:00:00Z"));
        } finally {
            clock.reset();
            fixtures.clearIssues();
        }
    }

    private String fingerprint(String table) {
        return jdbc.queryForObject("SELECT md5(string_agg(to_jsonb(t)::text, '' ORDER BY id)) FROM " + table + " t", String.class);
    }
}
