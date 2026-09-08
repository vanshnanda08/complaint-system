package com.civictrack.issue;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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


    // ------------------------------------------------------------------
    // phase 3: the SLA sweep
    // ------------------------------------------------------------------

    /**
     * Breached issues, row-locked, oldest deadline first.
     *
     * <p>The breach predicate is evaluated in SQL rather than in Java because
     * the alternative is loading every open issue to ask each one whether it is
     * late. It mirrors {@link com.civictrack.sla.SlaService#isBreached} exactly:
     * the clock has to be running -- PENDING_VERIFICATION is excluded, which is
     * what "the department is not charged for waiting on citizens" means in
     * practice -- and {@code paused_seconds} is added to the stored deadline
     * before the comparison. {@code DashboardBreachAgreementIT} pins all three
     * definitions together -- this query, {@link #findChronicBreachIds}, and
     * {@link com.civictrack.sla.SlaService#isBreached} -- so they cannot drift.
     * (An earlier version of this comment named {@code SlaBreachPredicateIT},
     * which never existed. See DD-028.)
     *
     * <p>{@code SKIP LOCKED} means two workers take disjoint slices instead of
     * queueing behind each other. It is a contention measure, not the
     * correctness measure: correctness comes from the compare-and-swap and the
     * unique constraint, both of which hold even if this query returned the
     * same row to both workers.
     */
    @Query(value = """
            SELECT i.id
            FROM issues i
            WHERE i.status IN ('NEW','ACKNOWLEDGED','ASSIGNED','IN_PROGRESS','REOPENED')
              AND i.escalation_level < :maxLevel
              AND CAST(:now AS timestamptz)
                  > i.due_at + make_interval(secs => i.paused_seconds)
            ORDER BY i.due_at ASC
            LIMIT :batch
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> lockBreachedIssueIds(@Param("now") Instant now,
                                    @Param("maxLevel") int maxLevel,
                                    @Param("batch") int batch);

    /**
     * Issues that have exhausted the ladder and are still breached.
     *
     * <p>DD-005: level 4 is terminal, so these stop escalating. They do not
     * stop being visible -- that is the whole point of capping rather than
     * letting the number climb. This list is what the public dashboard's
     * chronic-breach panel is built from.
     */
    @Query(value = """
            SELECT i.id
            FROM issues i
            WHERE i.status IN ('NEW','ACKNOWLEDGED','ASSIGNED','IN_PROGRESS','REOPENED')
              AND i.escalation_level >= :maxLevel
              AND CAST(:now AS timestamptz)
                  > i.due_at + make_interval(secs => i.paused_seconds)
            ORDER BY i.due_at ASC
            """, nativeQuery = true)
    List<UUID> findChronicBreachIds(@Param("now") Instant now, @Param("maxLevel") int maxLevel);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Issue i WHERE i.id = :id")
    Optional<Issue> findByIdForUpdate(@Param("id") UUID id);

    /**
     * The third idempotency layer: advance the level only if nobody else
     * already did.
     *
     * <p>{@code WHERE escalation_level = :expected} returns zero rows if a
     * concurrent transaction moved it first, and the caller treats zero as
     * "somebody beat me" and rolls back rather than writing a second
     * escalation. This is what makes running the sweep twice, three times, or
     * on two instances at once produce the same result as running it once.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Issue i
               SET i.escalationLevel = :next,
                   i.assignedTo = :owner,
                   i.lastEscalatedAt = :now,
                   i.dueAt = :newDue,
                   i.updatedAt = :now
             WHERE i.id = :id AND i.escalationLevel = :expected
            """)
    int advanceEscalation(@Param("id") UUID id,
                          @Param("expected") int expected,
                          @Param("next") int next,
                          @Param("owner") UUID owner,
                          @Param("newDue") Instant newDue,
                          @Param("now") Instant now);

    /**
     * A page of open issues for the priority recompute (DD-004).
     *
     * <p>Ordered by id rather than by anything mutable, because the sweep pages
     * through this set while updating the very columns it might otherwise be
     * ordered by -- which would let an issue be visited twice or skipped
     * entirely as its score changed underneath the cursor.
     */
    @Query(value = """
            SELECT i.id FROM issues i
            WHERE i.status NOT IN ('CLOSED','REJECTED','RESOLVED')
              AND i.id > CAST(:after AS uuid)
            ORDER BY i.id ASC
            LIMIT :batch
            """, nativeQuery = true)
    List<UUID> findOpenIdsAfter(@Param("after") UUID after, @Param("batch") int batch);

    List<Issue> findByIdIn(Collection<UUID> ids);

    /** Whether this citizen is one of the issue's reporters. Used by the verify guard. */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM reports r
                           WHERE r.issue_id = :issueId AND r.reporter_id = :userId)
            """, nativeQuery = true)
    boolean existsReportBy(@Param("issueId") UUID issueId, @Param("userId") UUID userId);


    // ------------------------------------------------------------------
    // phase 4: the public read surface
    //
    // Every public read goes through PUBLIC_SELECT. It is a compile-time
    // constant so the four endpoints that serve an issue to an anonymous
    // caller -- list, bbox, by id, by reference -- cannot drift apart in what
    // they expose. A field added to one is added to all four, and a field left
    // off is left off all four. Given that the thing being guarded here is
    // "no staff identity ever reaches an anonymous caller", four hand-copied
    // SELECT lists would have been the wrong shape for the risk.
    //
    // The join to departments is LEFT: issues.department_id is nullable, and
    // an unrouted issue is still public.
    // ------------------------------------------------------------------

    String PUBLIC_SELECT = """
            SELECT i.id                            AS id,
                   i.public_ref                    AS publicRef,
                   i.category_code                 AS categoryCode,
                   c.display_name                  AS categoryName,
                   c.merge_radius_m                AS mergeRadiusM,
                   c.max_extent_multiplier         AS maxExtentMultiplier,
                   i.ward_id                       AS wardId,
                   w.ward_number                   AS wardNumber,
                   w.name                          AS wardName,
                   i.department_id                 AS departmentId,
                   d.name                          AS departmentName,
                   i.status                        AS status,
                   i.priority                      AS priority,
                   i.priority_score                AS priorityScore,
                   ST_Y(i.centroid)                AS lat,
                   ST_X(i.centroid)                AS lng,
                   i.report_count                  AS reportCount,
                   i.distinct_reporter_count       AS distinctReporterCount,
                   i.needs_review                  AS needsReview,
                   i.review_reason                 AS reviewReason,
                   i.max_member_dist_m             AS maxMemberDistM,
                   i.sum_w                         AS sumW,
                   i.first_reported_at             AS firstReportedAt,
                   i.last_reported_at              AS lastReportedAt,
                   i.due_at                        AS dueAt,
                   i.paused_seconds                AS pausedSeconds,
                   i.escalation_level              AS escalationLevel,
                   i.resolved_at                   AS resolvedAt,
                   i.reopen_count                  AS reopenCount,
                   i.resolution_note               AS resolutionNote,
                   i.resolution_photo_url          AS resolutionPhotoUrl,
                   i.resolved_without_verification AS resolvedWithoutVerification
            FROM issues i
            JOIN categories c  ON c.code = i.category_code
            JOIN wards w       ON w.id   = i.ward_id
            LEFT JOIN departments d ON d.id = i.department_id
            """;

    /**
     * The three optional filters shared by the list and the bbox query.
     *
     * <p>Each is "parameter is null OR column matches", the same shape
     * {@link #findQueue} uses, so one prepared statement serves every
     * combination of filters instead of the query being assembled from
     * strings at runtime.
     */
    String PUBLIC_FILTERS = """
            WHERE (CAST(:status AS text)   IS NULL OR i.status        = CAST(:status AS text))
              AND (CAST(:category AS text) IS NULL OR i.category_code = CAST(:category AS text))
              AND (CAST(:wardId AS uuid)   IS NULL OR i.ward_id       = CAST(:wardId AS uuid))
            """;

    /**
     * Sorting, chosen by a bound parameter rather than by concatenating a
     * column name into the SQL.
     *
     * <p>A sort key arriving from a query string is untrusted input, and the
     * ordinary way to make it dynamic -- building "ORDER BY " + param -- is a
     * SQL injection in a place people forget to look because it is not a WHERE
     * clause. Every branch here is a literal in the compiled statement, so an
     * unrecognised value simply selects none of them and the query falls
     * through to the newest-first tiebreak.
     */
    String PUBLIC_ORDER = """
            ORDER BY
              CASE WHEN CAST(:sort AS text) = 'priority'
                   THEN i.priority_score END DESC NULLS LAST,
              CASE WHEN CAST(:sort AS text) = 'reporters'
                   THEN i.distinct_reporter_count END DESC NULLS LAST,
              CASE WHEN CAST(:sort AS text) = 'age'
                   THEN i.first_reported_at END ASC NULLS LAST,
              CASE WHEN CAST(:sort AS text) = 'deadline'
                   THEN i.due_at + make_interval(secs => i.paused_seconds) END ASC NULLS LAST,
              i.first_reported_at DESC,
              i.id ASC
            """;

    @Query(value = PUBLIC_SELECT + PUBLIC_FILTERS + PUBLIC_ORDER
                   + " LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<PublicIssueRow> findPublicPage(@Param("status") String status,
                                        @Param("category") String category,
                                        @Param("wardId") UUID wardId,
                                        @Param("sort") String sort,
                                        @Param("limit") int limit,
                                        @Param("offset") int offset);

    @Query(value = "SELECT COUNT(*) FROM issues i " + """
            WHERE (CAST(:status AS text)   IS NULL OR i.status        = CAST(:status AS text))
              AND (CAST(:category AS text) IS NULL OR i.category_code = CAST(:category AS text))
              AND (CAST(:wardId AS uuid)   IS NULL OR i.ward_id       = CAST(:wardId AS uuid))
            """, nativeQuery = true)
    long countPublic(@Param("status") String status,
                     @Param("category") String category,
                     @Param("wardId") UUID wardId);

    /**
     * Viewport-scoped issues for the map.
     *
     * <p>{@code &&} is the bounding-box overlap operator, which is what lets
     * the GiST index on the centroid answer the query; {@code ST_MakeEnvelope}
     * builds the viewport in the same SRID. The cap is applied by the caller
     * as a LIMIT rather than by refusing the request, because a map that
     * silently draws nothing outside 500 markers is worse than one that draws
     * 500 and says so.
     */
    @Query(value = PUBLIC_SELECT + """
            WHERE i.centroid && ST_MakeEnvelope(:west, :south, :east, :north, 4326)
              AND (CAST(:status AS text)   IS NULL OR i.status        = CAST(:status AS text))
              AND (CAST(:category AS text) IS NULL OR i.category_code = CAST(:category AS text))
            ORDER BY i.priority_score DESC, i.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<PublicIssueRow> findPublicInBbox(@Param("south") double south,
                                          @Param("west") double west,
                                          @Param("north") double north,
                                          @Param("east") double east,
                                          @Param("status") String status,
                                          @Param("category") String category,
                                          @Param("limit") int limit);

    @Query(value = PUBLIC_SELECT + " WHERE i.id = :id", nativeQuery = true)
    Optional<PublicIssueRow> findPublicById(@Param("id") UUID id);

    @Query(value = PUBLIC_SELECT + " WHERE i.public_ref = :ref", nativeQuery = true)
    Optional<PublicIssueRow> findPublicByRef(@Param("ref") String publicRef);

    /**
     * The public view of a known set of issues, for callers that already have
     * the ids and want to avoid a lookup per row.
     *
     * <p>"My reports" is the caller here: it pages over the citizen's own
     * reports and then needs each report's issue. Fetching those one at a time
     * would be an N+1 on a screen whose whole job is to list N things.
     */
    @Query(value = PUBLIC_SELECT + " WHERE i.id IN (:ids)", nativeQuery = true)
    List<PublicIssueRow> findPublicByIdIn(@Param("ids") Collection<UUID> ids);

    /**
     * How many issues are overdue right now.
     *
     * <p>The predicate is character-for-character the one in
     * {@link #lockBreachedIssueIds} and {@link #findChronicBreachIds}, minus
     * the escalation-level split that separates those two. That is not
     * copy-paste indiscipline -- it is the same fact asked as a count instead
     * of as a locked worklist, and {@code DashboardBreachAgreementIT} asserts
     * that this number equals the size of the union of the other two so the
     * three cannot drift.
     */
    @Query(value = """
            SELECT COUNT(*)
            FROM issues i
            WHERE i.status IN ('NEW','ACKNOWLEDGED','ASSIGNED','IN_PROGRESS','REOPENED')
              AND CAST(:now AS timestamptz)
                  > i.due_at + make_interval(secs => i.paused_seconds)
            """, nativeQuery = true)
    long countBreached(@Param("now") Instant now);

    @Query(value = """
            SELECT w.name AS name, COUNT(*) AS total
            FROM issues i JOIN wards w ON w.id = i.ward_id
            WHERE i.status IN ('NEW','ACKNOWLEDGED','ASSIGNED','IN_PROGRESS','REOPENED')
              AND CAST(:now AS timestamptz)
                  > i.due_at + make_interval(secs => i.paused_seconds)
            GROUP BY w.name ORDER BY total DESC, w.name ASC LIMIT 1
            """, nativeQuery = true)
    Optional<NamedCountRow> findTopBreachedWard(@Param("now") Instant now);

    @Query(value = """
            SELECT d.name AS name, COUNT(*) AS total
            FROM issues i JOIN departments d ON d.id = i.department_id
            WHERE i.status IN ('NEW','ACKNOWLEDGED','ASSIGNED','IN_PROGRESS','REOPENED')
              AND CAST(:now AS timestamptz)
                  > i.due_at + make_interval(secs => i.paused_seconds)
            GROUP BY d.name ORDER BY total DESC, d.name ASC LIMIT 1
            """, nativeQuery = true)
    Optional<NamedCountRow> findTopBreachedDepartment(@Param("now") Instant now);

    /**
     * Issues that have reached RESOLVED or CLOSED, ever.
     *
     * <p>Blueprint 3.1 makes this the landing hero's fallback when the overdue
     * query is unavailable, so it must not share a code path with it.
     */
    @Query(value = "SELECT COUNT(*) FROM issues WHERE status IN ('RESOLVED','CLOSED')",
           nativeQuery = true)
    long countResolved();

    /**
     * The staff queue, with the display columns blueprint 3.14 asks for.
     *
     * <p>{@code tab} is applied in SQL rather than by filtering the page
     * afterwards, for the same reason the department scope is: filtering after
     * the LIMIT returns short pages of a full queue and makes paging lie.
     *
     * <p>The landmark comes from a correlated subquery over the issue's
     * earliest report. It is on the report, not the issue -- an issue has as
     * many landmarks as it has reporters -- and the first one is what the
     * person who found the problem called the place.
     */
    @Query(value = """
            SELECT i.id                       AS id,
                   i.public_ref               AS publicRef,
                   i.category_code            AS categoryCode,
                   c.display_name             AS categoryName,
                   i.ward_id                  AS wardId,
                   w.name                     AS wardName,
                   i.department_id            AS departmentId,
                   i.status                   AS status,
                   i.priority                 AS priority,
                   i.priority_score           AS priorityScore,
                   ST_Y(i.centroid)           AS lat,
                   ST_X(i.centroid)           AS lng,
                   i.report_count             AS reportCount,
                   i.distinct_reporter_count  AS distinctReporterCount,
                   i.needs_review             AS needsReview,
                   i.review_reason            AS reviewReason,
                   i.first_reported_at        AS firstReportedAt,
                   i.due_at                   AS dueAt,
                   i.paused_seconds           AS pausedSeconds,
                   i.escalation_level         AS escalationLevel,
                   i.assigned_to              AS assignedTo,
                   fr.landmark                AS landmark
            FROM issues i
            JOIN categories c ON c.code = i.category_code
            JOIN wards w      ON w.id   = i.ward_id
            LEFT JOIN LATERAL (
                SELECT r.landmark FROM reports r
                WHERE r.issue_id = i.id
                ORDER BY r.created_at ASC, r.id ASC
                LIMIT 1
            ) fr ON TRUE
            WHERE i.status NOT IN ('CLOSED','REJECTED','RESOLVED')
              AND (CAST(:departmentId AS uuid) IS NULL OR i.department_id = CAST(:departmentId AS uuid))
              AND (CAST(:wardId AS uuid) IS NULL OR i.ward_id = CAST(:wardId AS uuid))
              AND (CAST(:assignedTo AS uuid) IS NULL OR i.assigned_to = CAST(:assignedTo AS uuid))
              AND (:unassignedOnly = FALSE OR i.assigned_to IS NULL)
            ORDER BY i.priority_score DESC, i.due_at ASC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<QueueRow> findQueueRows(@Param("departmentId") UUID departmentId,
                                 @Param("wardId") UUID wardId,
                                 @Param("assignedTo") UUID assignedTo,
                                 @Param("unassignedOnly") boolean unassignedOnly,
                                 @Param("limit") int limit,
                                 @Param("offset") int offset);

    Optional<Issue> findByPublicRef(String publicRef);

    /** One issue as an anonymous caller may see it. See {@link #PUBLIC_SELECT}. */
    interface PublicIssueRow {
        UUID getId();
        String getPublicRef();
        String getCategoryCode();
        String getCategoryName();
        int getMergeRadiusM();
        BigDecimal getMaxExtentMultiplier();
        UUID getWardId();
        int getWardNumber();
        String getWardName();
        UUID getDepartmentId();
        String getDepartmentName();
        String getStatus();
        String getPriority();
        BigDecimal getPriorityScore();
        double getLat();
        double getLng();
        int getReportCount();
        int getDistinctReporterCount();
        boolean getNeedsReview();
        String getReviewReason();
        double getMaxMemberDistM();
        double getSumW();
        Instant getFirstReportedAt();
        Instant getLastReportedAt();
        Instant getDueAt();
        long getPausedSeconds();
        int getEscalationLevel();
        Instant getResolvedAt();
        int getReopenCount();
        String getResolutionNote();
        String getResolutionPhotoUrl();
        boolean getResolvedWithoutVerification();
    }

    /** One row of the staff queue. Carries assignedTo; never leaves the staff API. */
    interface QueueRow {
        UUID getId();
        String getPublicRef();
        String getCategoryCode();
        String getCategoryName();
        UUID getWardId();
        String getWardName();
        UUID getDepartmentId();
        String getStatus();
        String getPriority();
        BigDecimal getPriorityScore();
        double getLat();
        double getLng();
        int getReportCount();
        int getDistinctReporterCount();
        boolean getNeedsReview();
        String getReviewReason();
        Instant getFirstReportedAt();
        Instant getDueAt();
        long getPausedSeconds();
        int getEscalationLevel();
        UUID getAssignedTo();
        String getLandmark();
    }

    /** A name and how many things carry it. Used by the dashboard's "top" tiles. */
    interface NamedCountRow {
        String getName();
        long getTotal();
    }

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
