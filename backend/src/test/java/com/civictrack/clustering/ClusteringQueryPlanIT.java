package com.civictrack.clustering;

import com.civictrack.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A regression guard on the query plan, promised by DD-010.
 *
 * <p>{@code ST_DWithin(centroid::geography, ...)} can only use an index whose
 * definition contains the same cast. Drop the {@code ::geography} from
 * {@code idx_issues_cluster_candidates} and everything still works, every other
 * test still passes, and the clustering query quietly degrades from a
 * millisecond to several hundred at city scale. That failure is invisible on a
 * laptop with two hundred rows, and it would surface as "the demo feels slow"
 * with no obvious cause.
 *
 * <p>So the assertion is on the plan itself. If someone edits the index, CI
 * fails with a message naming the reason rather than the demo getting slow.
 */
class ClusteringQueryPlanIT extends IntegrationTestBase {

    /**
     * Enough rows that a sequential scan is genuinely the more expensive option.
     * With a nearly empty table PostgreSQL will seq-scan whatever indexes exist,
     * and rightly so -- asserting on the plan at that size would prove nothing.
     */
    private static final int SEEDED_ISSUES = 5_000;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedEnoughIssuesToMakeTheIndexWorthUsing() {
        jdbc.update("DELETE FROM issue_status_history");
        jdbc.update("DELETE FROM reports");
        jdbc.update("DELETE FROM issues");

        jdbc.update("""
                INSERT INTO issues (public_ref, category_code, ward_id, status, centroid,
                                    sum_w, sum_wx, sum_wy, report_count, due_at,
                                    first_reported_at, last_reported_at)
                SELECT 'CT-2026-P' || lpad(g::text, 6, '0'),
                       'POTHOLE',
                       (SELECT id FROM wards WHERE ward_number = 1),
                       'NEW',
                       ST_SetSRID(ST_MakePoint(
                           75.79 + (g %% 500) * 0.00012,
                           30.905 + (g / 500) * 0.00012), 4326),
                       0.015625, 0.0, 0.0, 1, now() + interval '72 hours',
                       now(), now()
                FROM generate_series(1, %d) g
                """.formatted(SEEDED_ISSUES));

        // The planner chooses on statistics, so without this it is still
        // working from the empty-table estimates and the test measures nothing.
        jdbc.execute("ANALYZE issues");
    }

    @Test
    @DisplayName("DD-010: the clustering candidate query uses the GiST index, not a sequential scan")
    void candidateQueryUsesTheSpatialIndex() {
        List<String> plan = explainCandidateQuery();
        String planText = String.join("\n", plan);

        // Deliberately specific. A bare "contains Index Scan" would pass on
        // this plan no matter what the issues table did, because the ward
        // subquery contributes an "Index Scan using wards_ward_number_key"
        // in every case -- including the regression. The assertion has to name
        // the scan on ISSUES or it proves nothing.
        assertThat(planText)
                .as("""
                    the candidate query fell back to a sequential scan on issues. The usual cause \
                    is that idx_issues_cluster_candidates no longer contains the ::geography cast, \
                    so ST_DWithin(centroid::geography, ...) cannot match it. Full plan:
                    %s""".formatted(planText))
                .contains("Index Scan using idx_issues_cluster_candidates on issues")
                .doesNotContain("Seq Scan on issues");
    }

    @Test
    @DisplayName("DD-010: the plan names the composite index specifically")
    void planNamesTheCompositeIndex() {
        // Not just "an index" -- the composite one that carries category_code
        // and ward_id alongside the geography expression, so a single index
        // serves the whole predicate rather than a bitmap AND of three.
        //
        // The Index Cond is the part that matters: all three of category_code,
        // ward_id and the geography bounding-box operator (&&) must be resolved
        // by the index itself rather than left to a post-scan Filter.
        String planText = String.join("\n", explainCandidateQuery());

        assertThat(planText).contains("idx_issues_cluster_candidates");

        String indexCond = planText.lines()
                .filter(line -> line.contains("Index Cond") && line.contains("category_code"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no Index Cond resolved category_code; plan was:\n" + planText));

        assertThat(indexCond)
                .as("all three predicates should be answered by the one composite index")
                .contains("category_code")
                .contains("ward_id")
                .contains("(centroid)::geography");
    }

    /**
     * EXPLAIN of the real candidate query, with the FOR UPDATE clause removed.
     *
     * <p>The lock does not affect index selection and EXPLAIN cannot take row
     * locks outside a transaction, so dropping it is the accurate thing to do
     * here. Everything above it -- the predicate, the ordering, the limit -- is
     * character-for-character what {@code IssueRepository.lockMergeCandidateIds}
     * sends.
     */
    private List<String> explainCandidateQuery() {
        return jdbc.queryForList("""
                EXPLAIN
                SELECT i.id
                FROM issues i
                WHERE i.category_code = 'POTHOLE'
                  AND i.ward_id = (SELECT id FROM wards WHERE ward_number = 1)
                  AND (i.status IN ('NEW','ACKNOWLEDGED','ASSIGNED','IN_PROGRESS',
                                    'PENDING_VERIFICATION','REOPENED')
                       OR (i.status = 'RESOLVED'
                           AND i.resolved_at > now() - make_interval(days => 14)))
                  AND ST_DWithin(i.centroid::geography,
                                 ST_SetSRID(ST_MakePoint(75.8200, 30.9300), 4326)::geography,
                                 157.5)
                ORDER BY ST_Distance(i.centroid::geography,
                                     ST_SetSRID(ST_MakePoint(75.8200, 30.9300), 4326)::geography) ASC
                LIMIT 5
                """, String.class);
    }
}
