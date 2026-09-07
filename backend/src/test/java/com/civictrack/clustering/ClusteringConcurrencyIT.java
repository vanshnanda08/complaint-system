package com.civictrack.clustering;

import com.civictrack.IntegrationTestBase;
import com.civictrack.issue.Issue;
import com.civictrack.issue.IssueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The single most valuable test in the suite, and the one most likely to fail
 * against a first implementation.
 *
 * <p>The demo is literally three people submitting the same pothole at the same
 * moment. Without concurrency control each transaction finds no candidate,
 * each creates an issue, and the bug is demonstrated live on a projector. Two
 * mechanisms prevent it, and this test exercises both together:
 *
 * <ol>
 *   <li>a transaction-scoped advisory lock on a hash of (category, ~200 m
 *       cell), which serialises everyone reporting the same thing in the same
 *       place while leaving the rest of the city parallel;</li>
 *   <li>{@code FOR UPDATE} row locks on the chosen candidates, so two reports
 *       merging into the <em>same</em> issue cannot interleave their running-sum
 *       updates and lose one of them.</li>
 * </ol>
 *
 * <p>The pool size matters to what this proves. Hikari is capped at 8, and with
 * {@code open-in-view=true} the connection would be held for the whole request,
 * so twenty threads would exhaust the pool and block — and the failure would
 * look like a locking bug rather than a configuration one. That is why
 * {@code open-in-view=false} is a phase-1 setting and not a phase-2 discovery.
 */
class ClusteringConcurrencyIT extends IntegrationTestBase {

    @Autowired private ClusteringService clustering;
    @Autowired private IssueRepository issueRepo;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearIssues() {
        jdbc.update("DELETE FROM issue_status_history");
        jdbc.update("DELETE FROM reports");
        jdbc.update("DELETE FROM issues");
    }

    @ParameterizedTest(name = "{0} simultaneous identical reports produce exactly one issue")
    @ValueSource(ints = {2, 5, 10, 20, 50})
    @DisplayName("concurrentReportsProduceOneIssue")
    void concurrentReportsProduceOneIssue(int threads) throws Exception {
        // Every thread submits the identical coordinate, so there is no
        // distance ambiguity: any outcome other than one issue is a race.
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ClusterOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final String device = "device-" + i;
                futures.add(pool.submit((Callable<ClusterOutcome>) () -> {
                    ready.countDown();
                    // Release all threads at once. Staggered starts would let
                    // each transaction commit before the next began, which
                    // would pass without any concurrency control at all and
                    // prove nothing.
                    fire.await(30, TimeUnit.SECONDS);
                    try {
                        return clustering.ingest(command(device));
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        throw t;
                    }
                }));
            }

            assertThat(ready.await(30, TimeUnit.SECONDS))
                    .as("all %d workers reached the start line", threads).isTrue();
            fire.countDown();

            for (Future<ClusterOutcome> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(failure.get()).as("no worker threw").isNull();

        // ---- the assertions that matter --------------------------------

        List<Issue> issues = issueRepo.findAll();
        assertThat(issues)
                .as("%d identical simultaneous reports must produce exactly one issue", threads)
                .hasSize(1);

        Issue issue = issues.get(0);
        assertThat(issue.getReportCount())
                .as("every report must be counted; a lost update shows up here")
                .isEqualTo(threads);
        assertThat(issue.getDistinctReporterCount())
                .as("each worker used a distinct device id")
                .isEqualTo(threads);

        assertThat(reportRowCount()).isEqualTo(threads);

        // The running sums must reflect every merge. If two transactions
        // interleaved their read-modify-write of sum_w, this is where the loss
        // becomes visible -- the centroid would still look plausible, which is
        // exactly what makes the bug hard to spot without this assertion.
        double expectedSumW = threads * (1.0 / (8.0 * 8.0));
        assertThat(issue.getSumW()).isCloseTo(expectedSumW, within(1e-9));
    }

    @Test
    @DisplayName("reports in different cells are not serialised against each other")
    void differentCellsProceedIndependently() throws Exception {
        // The advisory lock must not serialise the whole city. These two points
        // are far enough apart to fall in different 0.002-degree cells, so they
        // take different locks and must both succeed without blocking.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch fire = new CountDownLatch(1);
            Future<ClusterOutcome> a = pool.submit(() -> {
                fire.await(10, TimeUnit.SECONDS);
                return clustering.ingest(commandAt(GeoFixtures.LAT, GeoFixtures.LNG, "device-a"));
            });
            Future<ClusterOutcome> b = pool.submit(() -> {
                fire.await(10, TimeUnit.SECONDS);
                return clustering.ingest(commandAt(
                        GeoFixtures.latOffsetM(2000), GeoFixtures.lngOffsetM(2000), "device-b"));
            });
            fire.countDown();

            assertThat(a.get(60, TimeUnit.SECONDS).issueId())
                    .isNotEqualTo(b.get(60, TimeUnit.SECONDS).issueId());
        } finally {
            pool.shutdownNow();
        }

        assertThat(issueRepo.count()).isEqualTo(2);
    }

    private IngestReportCommand command(String deviceId) {
        return commandAt(GeoFixtures.LAT, GeoFixtures.LNG, deviceId);
    }

    private IngestReportCommand commandAt(double lat, double lng, String deviceId) {
        return new IngestReportCommand(
                "POTHOLE", lat, lng, 8.0, false,
                "concurrent report", "Test Road", null,
                "https://example.test/photo.jpg", null, null, deviceId);
    }

    private int reportRowCount() {
        return jdbc.queryForObject("SELECT count(*) FROM reports", Integer.class);
    }
}
