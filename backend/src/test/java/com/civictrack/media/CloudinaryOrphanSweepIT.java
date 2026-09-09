package com.civictrack.media;

import com.civictrack.IntegrationTestBase;
import com.civictrack.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the orphan QUERY, which is the part that decides what gets deleted.
 *
 * <p>The Admin API calls are not exercised here, deliberately: mocking
 * Cloudinary's HTTP responses would test the mock. What can go catastrophically
 * wrong is the query -- a false positive deletes a live photo, silently, on a
 * schedule, at half past three in the morning. So that is what is pinned.
 *
 * <p>The third case is the one that earns its keep. The stored URL is a
 * DELIVERY url and now carries a transformation between {@code /upload/} and
 * the public id ({@code f_auto,q_auto,w_1600,c_limit}). Any matching strategy
 * that reconstructs the URL, or compares path segments positionally, breaks the
 * day that transformation changes -- and breaks by reporting live photos as
 * orphans.
 */
class CloudinaryOrphanSweepIT extends IntegrationTestBase {

    @Autowired private CloudinaryOrphanSweep sweep;
    @Autowired private Fixtures fixtures;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        fixtures.clearIssues();
    }

    /** A report whose photo_url is a real Cloudinary delivery URL. */
    private void reportWithPhoto(String photoUrl) {
        UUID issueId = jdbc.queryForObject("""
                INSERT INTO issues (public_ref, category_code, ward_id, status, centroid,
                                    sum_w, sum_wx, sum_wy, report_count, due_at,
                                    first_reported_at, last_reported_at)
                SELECT 'CT-2026-S' || lpad((floor(random()*899999)+100000)::int::text, 6, '0'),
                       'POTHOLE', (SELECT id FROM wards WHERE ward_number = 1), 'NEW',
                       ST_SetSRID(ST_MakePoint(75.82, 30.93), 4326),
                       0.015625, 0.0, 0.0, 1, now() + interval '72 hours', now(), now()
                RETURNING id
                """, UUID.class);
        jdbc.update("""
                INSERT INTO reports (issue_id, device_id, category_code, location,
                                     gps_accuracy_m, manual_pin, photo_url, cluster_decision,
                                     created_at)
                VALUES (?, 'device-test', 'POTHOLE',
                        ST_SetSRID(ST_MakePoint(75.82, 30.93), 4326),
                        8.0, false, ?, 'NEW_ISSUE', now())
                """, issueId, photoUrl);
    }

    @Test
    @DisplayName("an id no report references is reported as an orphan")
    void unreferencedIdIsAnOrphan() {
        assertThat(sweep.orphansAmong(List.of("nothing_points_at_this")))
                .containsExactly("nothing_points_at_this");
    }

    @Test
    @DisplayName("an id a report references is NOT an orphan")
    void referencedIdIsNotAnOrphan() {
        reportWithPhoto("https://res.cloudinary.com/fjavyddc/image/upload/v1788/keepme.jpg");
        assertThat(sweep.orphansAmong(List.of("keepme"))).isEmpty();
    }

    @Test
    @DisplayName("a delivery transformation in the URL does not make a live photo look orphaned")
    void transformationInTheUrlDoesNotHideTheReference() {
        reportWithPhoto("https://res.cloudinary.com/fjavyddc/image/upload/"
                        + "f_auto,q_auto,w_1600,c_limit/v1788976355/msszf5bzq5m4zttooovk.jpg");

        assertThat(sweep.orphansAmong(List.of("msszf5bzq5m4zttooovk")))
                .as("""
                    a live photo was reported as an orphan because its stored URL carries a \
                    delivery transformation. This job DELETES what this query returns, so a \
                    false positive here loses a citizen's evidence permanently.""")
                .isEmpty();
    }

    @Test
    @DisplayName("it separates orphans from referenced ids in one batch")
    void mixedBatchIsSeparatedCorrectly() {
        reportWithPhoto("https://res.cloudinary.com/fjavyddc/image/upload/f_auto/v1/alive_one.jpg");
        reportWithPhoto("https://res.cloudinary.com/fjavyddc/image/upload/f_auto/v1/alive_two.jpg");

        assertThat(sweep.orphansAmong(List.of("alive_one", "dead_one", "alive_two", "dead_two")))
                .containsExactlyInAnyOrder("dead_one", "dead_two");
    }

    @Test
    @DisplayName("an empty batch is an empty result")
    void emptyBatchIsAnEmptyResult() {
        assertThat(sweep.orphansAmong(List.of())).isEmpty();
    }

    @Test
    @DisplayName("with no credentials the sweep does nothing rather than failing")
    void noCredentialsMeansNoWork() {
        // The normal state locally, and anywhere uploads still use the
        // placeholder. It must be a no-op rather than an error, or every such
        // environment collects a nightly stack trace.
        CloudinaryOrphanSweep.Result r = sweep.sweep();
        assertThat(r.scanned()).isZero();
        assertThat(r.deleted()).isZero();
    }
}
