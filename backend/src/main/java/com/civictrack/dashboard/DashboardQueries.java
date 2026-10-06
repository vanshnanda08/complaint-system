package com.civictrack.dashboard;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The public dashboard's aggregate SQL (blueprint 3.8, phase 7).
 *
 * <p>Kept out of {@code IssueRepository} because none of it maps to an entity
 * and all of it is read-only reporting; a projection interface per tile would
 * be ceremony around what is plainly a set of queries.
 *
 * <p>Four definitions are choices, stated here once rather than rediscovered
 * from the SQL (DD-064):
 *
 * <ul>
 *   <li><b>Resolved</b> means RESOLVED or CLOSED. Not REJECTED: a rejected
 *       issue was turned down, or merged away as a duplicate, and neither is a
 *       fix.</li>
 *   <li><b>Resolution time</b> is wall-clock, first report to resolution. It
 *       includes the time spent waiting on citizens, because that is the time
 *       a citizen actually waited.</li>
 *   <li><b>On time</b> is judged at the claim, not the resolution: the
 *       department claimed the fix -- {@code clock_paused_at}, which stays set
 *       from the final submission onwards -- before the effective deadline.
 *       The days citizens took to answer are not the department's lateness,
 *       which is exactly why the SLA clock pauses for them.</li>
 *   <li><b>A day</b> is a calendar day in {@link DashboardProperties#zone()}.</li>
 * </ul>
 *
 * <p>Every series is built on {@code generate_series}, so a day with nothing
 * in it is a zero row rather than a missing one. A chart that silently drops
 * empty days draws a line straight across them.
 */
@Repository
@RequiredArgsConstructor
public class DashboardQueries {

    /** The SLA clock is running in exactly these. Matches IssueRepository.countBreached. */
    private static final String CLOCK_RUNNING = "('NEW','ACKNOWLEDGED','ASSIGNED','IN_PROGRESS','REOPENED')";
    private static final String RESOLVED = "('RESOLVED','CLOSED')";
    private static final String OPEN = "i.status NOT IN ('RESOLVED','CLOSED','REJECTED')";
    private static final String ON_TIME =
            "COALESCE(i.clock_paused_at, i.resolved_at) <= i.due_at + make_interval(secs => i.paused_seconds)";
    private static final String HOURS_TO_RESOLVE =
            "EXTRACT(EPOCH FROM (i.resolved_at - i.first_reported_at)) / 3600.0";

    private final NamedParameterJdbcTemplate jdbc;
    private final DashboardProperties props;

    public DashboardMetricsDto.Median overallMedian() {
        return jdbc.queryForObject("SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY " + HOURS_TO_RESOLVE
                        + ") AS median_hours, COUNT(*) AS resolved FROM issues i"
                        + " WHERE i.status IN " + RESOLVED + " AND i.resolved_at IS NOT NULL",
                new MapSqlParameterSource(),
                (rs, n) -> median("All departments", rs.getObject("median_hours", Double.class),
                        rs.getLong("resolved")));
    }

    /** Every department, including those with nothing resolved: an absent row would read as a perfect one. */
    public List<DashboardMetricsDto.Median> medianByDepartment() {
        return jdbc.query("""
                SELECT d.name AS name,
                       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY %s) AS median_hours,
                       COUNT(i.id) AS resolved
                FROM departments d
                LEFT JOIN issues i ON i.department_id = d.id
                                  AND i.status IN %s AND i.resolved_at IS NOT NULL
                GROUP BY d.name ORDER BY d.name
                """.formatted(HOURS_TO_RESOLVE, RESOLVED), new MapSqlParameterSource(),
                (rs, n) -> median(rs.getString("name"), rs.getObject("median_hours", Double.class),
                        rs.getLong("resolved")));
    }

    public List<DashboardMetricsDto.Median> medianByWard() {
        return jdbc.query("""
                SELECT w.name AS name,
                       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY %s) AS median_hours,
                       COUNT(i.id) AS resolved
                FROM wards w
                LEFT JOIN issues i ON i.ward_id = w.id
                                  AND i.status IN %s AND i.resolved_at IS NOT NULL
                GROUP BY w.name, w.ward_number ORDER BY w.ward_number
                """.formatted(HOURS_TO_RESOLVE, RESOLVED), new MapSqlParameterSource(),
                (rs, n) -> median(rs.getString("name"), rs.getObject("median_hours", Double.class),
                        rs.getLong("resolved")));
    }

    public DashboardMetricsDto.SlaCompliance slaCompliance(Instant now) {
        long[] totals = jdbc.queryForObject("""
                SELECT COUNT(*) AS resolved, COUNT(*) FILTER (WHERE %s) AS on_time
                FROM issues i WHERE i.status IN %s
                """.formatted(ON_TIME, RESOLVED), new MapSqlParameterSource(),
                (rs, n) -> new long[]{rs.getLong("resolved"), rs.getLong("on_time")});

        List<DashboardMetricsDto.ComplianceDay> trend = jdbc.query("""
                WITH days AS (
                    SELECT generate_series(CAST(:today AS date) - (:n - 1), CAST(:today AS date),
                                           interval '1 day')::date AS day)
                SELECT d.day AS day,
                       COUNT(i.id) AS resolved,
                       COUNT(i.id) FILTER (WHERE %s) AS on_time
                FROM days d
                LEFT JOIN issues i ON i.status IN %s
                                  AND (i.resolved_at AT TIME ZONE :zone)::date = d.day
                GROUP BY d.day ORDER BY d.day
                """.formatted(ON_TIME, RESOLVED), days(now, props.complianceDays()),
                (rs, n) -> new DashboardMetricsDto.ComplianceDay(rs.getObject("day", java.time.LocalDate.class),
                        rs.getLong("resolved"), rs.getLong("on_time")));

        Double rate = totals[0] < props.minimumSample() ? null : (double) totals[1] / totals[0];
        return new DashboardMetricsDto.SlaCompliance(totals[0], totals[1], rate, trend);
    }

    /** Open issues by how long ago they were first reported. Buckets are [from, to) in days. */
    public List<DashboardMetricsDto.AgeBucket> backlogAge(Instant now) {
        return jdbc.queryForObject("""
                WITH open AS (
                    SELECT EXTRACT(EPOCH FROM (CAST(:now AS timestamptz) - i.first_reported_at)) / 86400.0 AS age
                    FROM issues i WHERE %s)
                SELECT COUNT(*) FILTER (WHERE age < 1)                AS b0,
                       COUNT(*) FILTER (WHERE age >= 1  AND age < 3)  AS b1,
                       COUNT(*) FILTER (WHERE age >= 3  AND age < 7)  AS b2,
                       COUNT(*) FILTER (WHERE age >= 7  AND age < 14) AS b3,
                       COUNT(*) FILTER (WHERE age >= 14 AND age < 30) AS b4,
                       COUNT(*) FILTER (WHERE age >= 30)              AS b5
                FROM open
                """.formatted(OPEN), new MapSqlParameterSource("now", Timestamp.from(now)),
                (rs, n) -> List.of(
                        new DashboardMetricsDto.AgeBucket("Under a day", 0, 1, rs.getLong("b0")),
                        new DashboardMetricsDto.AgeBucket("1–3 days", 1, 3, rs.getLong("b1")),
                        new DashboardMetricsDto.AgeBucket("3–7 days", 3, 7, rs.getLong("b2")),
                        new DashboardMetricsDto.AgeBucket("1–2 weeks", 7, 14, rs.getLong("b3")),
                        new DashboardMetricsDto.AgeBucket("2–4 weeks", 14, 30, rs.getLong("b4")),
                        new DashboardMetricsDto.AgeBucket("Over 30 days", 30, null, rs.getLong("b5"))));
    }

    /**
     * New issues and resolutions per day. Issues on both sides, so the two
     * lines are in the same unit: a line of reports against a line of issues
     * would show a gap that is only the clustering doing its job.
     */
    public List<DashboardMetricsDto.DayCount> reportedVsResolved(Instant now) {
        return jdbc.query("""
                WITH days AS (
                    SELECT generate_series(CAST(:today AS date) - (:n - 1), CAST(:today AS date),
                                           interval '1 day')::date AS day),
                reported AS (
                    SELECT (i.first_reported_at AT TIME ZONE :zone)::date AS day, COUNT(*) AS c
                    FROM issues i WHERE i.merged_into_id IS NULL GROUP BY 1),
                resolved AS (
                    SELECT (i.resolved_at AT TIME ZONE :zone)::date AS day, COUNT(*) AS c
                    FROM issues i WHERE i.status IN %s AND i.resolved_at IS NOT NULL GROUP BY 1)
                SELECT d.day AS day, COALESCE(rp.c, 0) AS reported, COALESCE(rs.c, 0) AS resolved
                FROM days d
                LEFT JOIN reported rp ON rp.day = d.day
                LEFT JOIN resolved rs ON rs.day = d.day
                ORDER BY d.day
                """.formatted(RESOLVED), days(now, props.trendDays()),
                (rs, n) -> new DashboardMetricsDto.DayCount(rs.getObject("day", java.time.LocalDate.class),
                        rs.getLong("reported"), rs.getLong("resolved")));
    }

    /** Open issues with the most distinct people behind them. */
    public List<DashboardMetricsDto.TopCluster> topClusters() {
        return jdbc.query("""
                SELECT i.id, i.public_ref, c.display_name, w.name AS ward_name, i.status,
                       i.distinct_reporter_count, i.report_count
                FROM issues i
                JOIN categories c ON c.code = i.category_code
                JOIN wards w ON w.id = i.ward_id
                WHERE %s
                ORDER BY i.distinct_reporter_count DESC, i.report_count DESC, i.first_reported_at ASC
                LIMIT :limit
                """.formatted(OPEN), new MapSqlParameterSource("limit", props.topClusters()),
                (rs, n) -> new DashboardMetricsDto.TopCluster(rs.getObject("id", UUID.class),
                        rs.getString("public_ref"), rs.getString("display_name"), rs.getString("ward_name"),
                        rs.getString("status"), rs.getInt("distinct_reporter_count"), rs.getInt("report_count")));
    }

    /** Breached and on the clock, most overdue first. Same predicate as the overdue count, so the list and the tile agree. */
    public List<BreachingIssueDto> breaching(Instant now) {
        return jdbc.query("""
                SELECT i.id, i.public_ref, c.display_name, w.name AS ward_name, d.name AS department_name,
                       i.status, i.due_at + make_interval(secs => i.paused_seconds) AS deadline,
                       EXTRACT(EPOCH FROM (CAST(:now AS timestamptz)
                               - (i.due_at + make_interval(secs => i.paused_seconds))))::bigint AS overdue,
                       i.escalation_level, i.distinct_reporter_count
                FROM issues i
                JOIN categories c ON c.code = i.category_code
                JOIN wards w ON w.id = i.ward_id
                LEFT JOIN departments d ON d.id = i.department_id
                WHERE i.status IN %s
                  AND CAST(:now AS timestamptz) > i.due_at + make_interval(secs => i.paused_seconds)
                ORDER BY overdue DESC, i.id
                LIMIT :limit
                """.formatted(CLOCK_RUNNING),
                new MapSqlParameterSource("now", Timestamp.from(now)).addValue("limit", props.breachingLimit()),
                (rs, n) -> new BreachingIssueDto(rs.getObject("id", UUID.class), rs.getString("public_ref"),
                        rs.getString("display_name"), rs.getString("ward_name"), rs.getString("department_name"),
                        rs.getString("status"), rs.getTimestamp("deadline").toInstant(), rs.getLong("overdue"),
                        rs.getInt("escalation_level"), rs.getInt("distinct_reporter_count")));
    }

    private MapSqlParameterSource days(Instant now, int n) {
        // "Today" in the configured zone, computed from the injected clock
        // rather than the database's now(), so a test can pin it.
        java.time.LocalDate today = now.atZone(java.time.ZoneId.of(props.zone())).toLocalDate();
        return new MapSqlParameterSource("today", today)
                .addValue("n", n)
                .addValue("zone", props.zone());
    }

    private DashboardMetricsDto.Median median(String name, Double hours, long resolved) {
        boolean enough = resolved >= props.minimumSample() && hours != null;
        return new DashboardMetricsDto.Median(name, enough ? Math.round(hours * 10.0) / 10.0 : null, resolved);
    }
}
