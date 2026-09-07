package com.civictrack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that Flyway owns a schema the application can actually start against.
 *
 * <p>Two of these assertions are regression guards rather than sanity checks.
 * The index-definition test fails if anyone removes the {@code ::geography}
 * cast from the candidate index, which would silently drop the clustering
 * query to a sequential scan — invisible on a laptop with 200 rows, fatal at
 * 50k issues. The extension test fails if {@code btree_gist} is dropped, which
 * would make the composite index impossible to build in the first place.
 */
class SchemaMigrationIT extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("PostGIS, pgcrypto and btree_gist are all installed")
    void requiredExtensionsInstalled() {
        List<String> extensions = jdbc.queryForList(
                "SELECT extname FROM pg_extension", String.class);

        assertThat(extensions).contains("postgis", "pgcrypto", "btree_gist");
    }

    @Test
    @DisplayName("all eleven domain tables exist")
    void domainTablesExist() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                """, String.class);

        assertThat(tables).contains(
                "users", "departments", "wards", "categories",
                "reports", "issues", "issue_status_history",
                "escalation_events", "verifications", "notifications", "shedlock");
    }

    @Test
    @DisplayName("the clustering candidate index keeps the cast inside the index definition")
    void candidateIndexIncludesGeographyCast() {
        String indexDef = jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE indexname = 'idx_issues_cluster_candidates'
                """, String.class);

        assertThat(indexDef)
                .as("ST_DWithin(centroid::geography, ...) cannot use an index built on the "
                    + "bare geometry; without the cast the planner falls back to a seq scan")
                .contains("gist")
                .contains("(centroid)::geography")
                .contains("category_code")
                .contains("ward_id");
    }

    @Test
    @DisplayName("reference data is seeded and every tunable number is present on it")
    void referenceDataSeeded() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM categories", Integer.class))
                .isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wards", Integer.class))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM departments", Integer.class))
                .isEqualTo(7);

        // Standing rule 1: these live in the table, so nothing is null.
        Integer incomplete = jdbc.queryForObject("""
                SELECT count(*) FROM categories
                WHERE merge_radius_m IS NULL OR default_sla_hours IS NULL
                   OR max_extent_multiplier IS NULL OR low_conf_action IS NULL
                   OR reopen_window_days IS NULL OR department_id IS NULL
                """, Integer.class);
        assertThat(incomplete).isZero();
    }

    @Test
    @DisplayName("DD-002: exactly the three categories the rule names take SPLIT_FLAG")
    void splitFlagAppliesToExactlyTheRuledCategories() {
        // The rule has three clauses, one per category below. Asserting the
        // whole set rather than a sample matters: the seed and the written rule
        // drifted apart once already, in both directions, and a test naming
        // only OPEN_MANHOLE would not have caught either half of that.
        assertThat(jdbc.queryForList(
                "SELECT code FROM categories WHERE low_conf_action = 'SPLIT_FLAG' ORDER BY code",
                String.class))
                .as("safety-critical, property-damage, and mobile-target respectively")
                .containsExactly("OPEN_MANHOLE", "STRAY_ANIMAL", "WATER_LEAK");

        // The optimistic path is still the default for everything else; a wrong
        // merge there costs one click to split.
        assertThat(lowConfActionOf("POTHOLE")).isEqualTo("MERGE_FLAG");
        assertThat(lowConfActionOf("DRAINAGE_BLOCK")).isEqualTo("MERGE_FLAG");
    }

    @Test
    @DisplayName("DD-002: WATER_LEAK carries the severity that justifies its band policy")
    void waterLeakIsTheHighestSeverityPropertyDamageCategory() {
        // The property-damage clause rests on the claim that a concealed second
        // leak keeps causing damage. If WATER_LEAK's severity or SLA were ever
        // retuned downward, that argument would need revisiting rather than
        // silently continuing to hold.
        assertThat(jdbc.queryForObject(
                "SELECT severity_weight FROM categories WHERE code = 'WATER_LEAK'", Integer.class))
                .isEqualTo(20);
        assertThat(jdbc.queryForObject(
                "SELECT default_sla_hours FROM categories WHERE code = 'WATER_LEAK'", Integer.class))
                .isEqualTo(24);
    }

    @Test
    @DisplayName("DD-005: the escalation level is capped at 4 by the database, not only by code")
    void escalationLevelIsCappedBySchema() {
        String constraint = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'chk_escalation_level'
                """, String.class);

        // A code path that forgets the cap must fail loudly here rather than
        // producing an issue at level 7 that resolves to no owner at all.
        assertThat(constraint).contains("escalation_level").contains("4");
    }

    private String lowConfActionOf(String code) {
        return jdbc.queryForObject(
                "SELECT low_conf_action FROM categories WHERE code = ?", String.class, code);
    }
}
