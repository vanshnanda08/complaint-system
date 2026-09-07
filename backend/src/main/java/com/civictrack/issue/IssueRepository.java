package com.civictrack.issue;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface IssueRepository extends JpaRepository<Issue, UUID> {

    /**
     * Serialises every transaction that could plausibly interact, by taking a
     * transaction-scoped advisory lock on a hash of (category, ~200 m cell).
     *
     * <p>Without this, three phones submitting the same pothole at the same
     * moment each find no candidate and each create an issue -- the bug demoed
     * live. {@code ST_SnapToGrid} at 0.002 degrees is roughly a 200 m x 220 m
     * cell, so simultaneous reports of the same thing serialise while reports
     * elsewhere in the city proceed in parallel. The lock releases with the
     * transaction; there is nothing to unlock by hand.
     *
     * <p>Known limitation, stated rather than hidden: two reports straddling a
     * cell boundary can still race. The cell is roughly eight times the largest
     * merge radius so the probability is low, and the failure mode is benign --
     * a duplicate issue that the review queue catches. Locking the 3x3
     * neighbourhood would eliminate it at nine times the serialised area.
     */
    @Query(value = """
            SELECT pg_advisory_xact_lock(
              hashtext(:cat || ':' ||
                ST_AsText(ST_SnapToGrid(
                  ST_SetSRID(ST_MakePoint(:lng, :lat), 4326), :grid))))
            """, nativeQuery = true)
    void acquireCellLock(@Param("cat") String categoryCode,
                         @Param("lat") double lat,
                         @Param("lng") double lng,
                         @Param("grid") double gridDegrees);

    /**
     * Phase one of the two-phase candidate lookup: find the nearest open
     * candidates and row-lock them, returning only their ids.
     *
     * <p>Only ids are returned deliberately. PostgreSQL evaluates ORDER BY and
     * LIMIT <em>before</em> acquiring row locks, so any distance or sum_w this
     * query computed may be stale by the time the lock is granted (DD-003).
     * Returning those values would invite a caller to trust them. The caller
     * must re-read via {@link #reReadLockedCandidates}.
     *
     * <p>{@code ST_DWithin} is the index-using predicate -- it applies a
     * bounding-box prefilter through the composite GiST index -- while
     * {@code ST_Distance} in the ORDER BY runs only on the survivors.
     */
    @Query(value = """
            SELECT i.id
            FROM issues i
            WHERE i.category_code = :cat
              AND i.ward_id = :wardId
              AND (i.status IN ('NEW','ACKNOWLEDGED','ASSIGNED','IN_PROGRESS',
                                'PENDING_VERIFICATION','REOPENED')
                   OR (i.status = 'RESOLVED'
                       AND i.resolved_at > now() - make_interval(days => :reopenDays)))
              AND ST_DWithin(i.centroid::geography,
                             ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                             :radius)
            ORDER BY ST_Distance(i.centroid::geography,
                                 ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) ASC
            LIMIT 5
            FOR UPDATE OF i
            """, nativeQuery = true)
    List<UUID> lockMergeCandidateIds(@Param("cat") String cat,
                                     @Param("wardId") UUID wardId,
                                     @Param("lat") double lat,
                                     @Param("lng") double lng,
                                     @Param("radius") double radius,
                                     @Param("reopenDays") int reopenDays);

    /**
     * Phase two, and the whole point of DD-003: re-read the locked rows and
     * recompute the distance from values that are now guaranteed stable.
     *
     * <p>Between the ordering in {@link #lockMergeCandidateIds} and the grant
     * of its row locks, a concurrent transaction in an <em>adjacent</em> cell
     * -- which the advisory lock deliberately allows to run in parallel -- can
     * commit a centroid update to one of these very rows. This query runs after
     * the locks are held, so no further update can intervene, and every value
     * it returns is the value the merge decision is entitled to rely on.
     *
     * <p>The band decision must use only what comes back from here. Nothing
     * computed before the locks were granted is trustworthy.
     */
    @Query(value = """
            SELECT i.id                                          AS id,
                   ST_Distance(i.centroid::geography,
                       ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distanceM,
                   i.sum_w                                       AS sumW,
                   i.sum_wx                                      AS sumWx,
                   i.sum_wy                                      AS sumWy,
                   i.max_member_dist_m                           AS maxMemberDistM,
                   ST_Y(i.centroid)                              AS centroidLat,
                   ST_X(i.centroid)                              AS centroidLng,
                   i.status                                      AS status,
                   i.resolved_at                                 AS resolvedAt,
                   i.report_count                                AS reportCount
            FROM issues i
            WHERE i.id IN (:ids)
            ORDER BY distanceM ASC
            """, nativeQuery = true)
    List<CandidateRow> reReadLockedCandidates(@Param("ids") Collection<UUID> ids,
                                              @Param("lat") double lat,
                                              @Param("lng") double lng);

    /**
     * Geodesic distances needed for the extent-cap projection (DD-001),
     * computed by PostGIS in one round trip.
     *
     * <p>Standing rule 2: these are metres on the spheroid. Computing them in
     * Java from SRID 4326 degrees would be wrong by a latitude-dependent
     * factor, and wrong in a way that grows with distance from the equator.
     */
    @Query(value = """
            SELECT ST_Distance(
                       ST_SetSRID(ST_MakePoint(:oldLng, :oldLat), 4326)::geography,
                       ST_SetSRID(ST_MakePoint(:newLng, :newLat), 4326)::geography) AS centroidShiftM,
                   ST_Distance(
                       ST_SetSRID(ST_MakePoint(:newLng, :newLat), 4326)::geography,
                       ST_SetSRID(ST_MakePoint(:rptLng, :rptLat), 4326)::geography) AS newMemberDistM
            """, nativeQuery = true)
    ExtentProjectionRow projectExtent(@Param("oldLat") double oldLat, @Param("oldLng") double oldLng,
                                      @Param("newLat") double newLat, @Param("newLng") double newLng,
                                      @Param("rptLat") double rptLat, @Param("rptLng") double rptLng);

    /**
     * Counts distinct reporters on one issue.
     *
     * <p>Anonymous reporters are counted by device, so twenty submissions from
     * one phone are one reporter. This is the value priority scales on, which
     * is what stops a single person refreshing the form to buy urgency.
     *
     * <p>Unlike the centroid this is not maintained incrementally: a set
     * cardinality cannot be updated in O(1) without storing the set. It is an
     * indexed lookup over one issue's reports, and report counts per issue are
     * small, so the cost is negligible against the correctness of the number.
     *
     * <p>Native, so it reads committed table state rather than the persistence
     * context. Callers must flush the new report first or it will not be
     * counted.
     */
    @Query(value = """
            SELECT COUNT(DISTINCT COALESCE(r.reporter_id::text, r.device_id))
            FROM reports r WHERE r.issue_id = :issueId
            """, nativeQuery = true)
    int countDistinctReporters(@Param("issueId") UUID issueId);

    /** Issues a public ticket reference. Never reused, never reassigned. */
    @Query(value = """
            SELECT 'CT-' || to_char(now(), 'YYYY') || '-' ||
                   lpad(nextval('issue_public_ref_seq')::text, 6, '0')
            """, nativeQuery = true)
    String nextPublicRef();

    /** Values re-read under the row lock. See {@link #reReadLockedCandidates}. */
    interface CandidateRow {
        UUID getId();
        double getDistanceM();
        double getSumW();
        double getSumWx();
        double getSumWy();
        double getMaxMemberDistM();
        double getCentroidLat();
        double getCentroidLng();
        String getStatus();
        Instant getResolvedAt();
        int getReportCount();
    }

    interface ExtentProjectionRow {
        double getCentroidShiftM();
        double getNewMemberDistM();
    }
}
