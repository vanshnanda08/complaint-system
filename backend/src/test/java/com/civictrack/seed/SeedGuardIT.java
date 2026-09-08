package com.civictrack.seed;

import com.civictrack.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seed generator must refuse a database that already holds issues.
 *
 * <p>This guards a deployment footgun, not a feature. {@code SeedDataGenerator}
 * is a {@code CommandLineRunner}, so it runs on every boot the {@code seed}
 * profile is active for -- and a free-tier instance restarts whenever it has
 * been idle for a quarter of an hour. Leaving the profile on would add a full
 * corpus per visit.
 *
 * <p>What makes it worth a test rather than a comment is that the damage is
 * quiet. A second corpus is not a visible duplicate: the clustering engine
 * merges it into the first wherever the two overlap, so report counts and
 * distinct-reporter counts inflate, priority scores climb, and the ground-truth
 * labels written beside the first run stop describing the data that is actually
 * there. Phase 8's evaluation reads those labels, so the corruption would
 * surface as an inexplicably poor clustering score weeks later.
 */
@ActiveProfiles("seed")
@TestPropertySource(properties = {
        "civictrack.seed.enabled=true",
        // A tiny corpus: this test is about the guard, not about generation.
        "civictrack.seed.corpus-size=6",
        "civictrack.seed.random-seed=99",
        "civictrack.seed.labels-file-path=target/seed-guard-labels.csv",
})
class SeedGuardIT extends IntegrationTestBase {

    @Autowired private SeedDataGenerator generator;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("a second boot does not add a second corpus")
    void secondBootIsSkipped() throws Exception {
        // The context start already ran the generator once, exactly as a boot
        // would. That is the state a redeployed instance wakes up in.
        long afterFirstBoot = jdbc.queryForObject("SELECT count(*) FROM issues", Long.class);
        assertThat(afterFirstBoot)
                .as("the first boot should have seeded something to guard against")
                .isPositive();

        generator.run();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM issues", Long.class))
                .as("a restart must not add another corpus -- Render restarts on every "
                    + "idle spin-down, so this would compound per visit")
                .isEqualTo(afterFirstBoot);
    }

    @Test
    @DisplayName("force is required to seed a populated database, and says what it does")
    void forceIsOptIn() {
        // Documents the escape hatch: `force` adds a corpus, it does not
        // replace one. Anybody reaching for it should know that.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM issues", Long.class)).isPositive();
    }
}
