package com.civictrack.seed;

import com.civictrack.category.Category;
import com.civictrack.category.CategoryRepository;
import com.civictrack.clustering.ClusterOutcome;
import com.civictrack.clustering.ClusteringService;
import com.civictrack.clustering.IngestReportCommand;
import com.civictrack.common.geo.GeoFactory;
import com.civictrack.issue.IssueLifecycleService;
import com.civictrack.user.Actor;
import com.civictrack.user.Role;
import com.civictrack.ward.Ward;
import com.civictrack.ward.WardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component
@Profile("seed")
@RequiredArgsConstructor
@Slf4j
public class SeedDataGenerator implements CommandLineRunner {

    /** Stand-in photo for seeded reports. Must be a URL that resolves. */
    private static final String SEED_PHOTO_URL =
            "https://res.cloudinary.com/demo/image/upload/sample.jpg";

    private final SeedProperties props;
    private final CategoryRepository categoryRepo;
    private final WardRepository wardRepo;
    private final ClusteringService clusteringService;
    private final IssueLifecycleService lifecycleService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Standing rule: time comes from the injected clock, never from
     * {@code Instant.now()}. It matters here even though this is a generator
     * rather than a service -- the corpus is spread backwards over a window
     * from "now", so that origin is the one thing every simulated timestamp
     * derives from.
     *
     * <p>THE CORPUS IS NOT CURRENTLY REPRODUCIBLE, and an earlier version of
     * this comment claimed it was. Measured, by fingerprinting the corpus and
     * reseeding: two runs of the same configuration produce different
     * fingerprints and different label files. Three things break it, and the
     * fixed {@code random-seed} addresses none of them:
     *
     * <ul>
     *   <li>Primary keys are {@code @GeneratedValue(GenerationType.UUID)} and
     *       {@code gen_random_uuid()}. Nothing seeds either, so every run
     *       assigns different ids and different {@code public_ref} values.
     *   <li>The clock bean is {@code Clock.systemUTC()}. The 90-day window
     *       slides with wall time, so which issues fall the far side of the
     *       "older than 14 days" cutoff changes between runs.
     *   <li>{@code random-seed} governs only the {@code java.util.Random} draws
     *       -- locations, cluster sizes, report times. It has no reach into
     *       either of the above.
     * </ul>
     *
     * <p>Phase 8's evaluation is documented as depending on reproducibility, so
     * this has to be closed before that phase and not at it. Closing it means
     * a fixed {@code Clock} bean under the seed profile and seeded id
     * generation for issues and reports -- neither hard, but both are real
     * changes to how the application is wired, and doing them silently as part
     * of a corpus-size change would be the wrong place. Recorded as DD-046.
     */
    private final java.time.Clock clock;

    record GeneratedReport(
        UUID defectId,
        String categoryCode,
        double lat,
        double lng,
        double accuracy,
        Instant simulatedTime,
        UUID reporterId,
        String deviceId
    ) {}

    @Override
    public void run(String... args) throws Exception {
        if (!props.enabled()) {
            return;
        }

        // Refuse to seed a database that already holds issues.
        //
        // This generator is a CommandLineRunner, so it fires on every boot the
        // `seed` profile is active for -- and a deployed instance restarts far
        // more often than anybody expects. Render's free tier spins down after
        // fifteen minutes idle and boots again on the next request, so a
        // profile left switched on would add another full corpus every time
        // somebody visited the site.
        //
        // The damage would not be obvious either: the clustering engine would
        // merge the second corpus into the first wherever they overlap, so
        // report counts and distinct-reporter counts would inflate, priority
        // scores would climb, and the ground-truth labels written alongside
        // the first run would silently stop describing the data. Phase 8's
        // evaluation reads those labels.
        //
        // `force` exists for the deliberate case -- reseeding a scratch
        // database -- and has to be asked for by name.
        long existing = jdbcTemplate.queryForObject("SELECT count(*) FROM issues", Long.class);
        if (existing > 0 && !props.force()) {
            log.warn("Seed skipped: {} issues already exist. "
                     + "Set civictrack.seed.force=true to seed anyway (it will ADD a second "
                     + "corpus, not replace the first).", existing);
            return;
        }

        log.info("Starting Seed Data Generation... Corpus size: {}", props.corpusSize());

        Random random = new Random(props.randomSeed());
        List<Category> categories = categoryRepo.findAll();
        List<Ward> wards = wardRepo.findAll();

        if (categories.isEmpty() || wards.isEmpty()) {
            log.error("Reference data (categories/wards) not found. Cannot seed.");
            return;
        }

        List<GeneratedReport> reportsToIngest = new ArrayList<>();
        Instant now = clock.instant();

        int targetReports = props.corpusSize();
        int generatedCount = 0;

        while (generatedCount < targetReports) {
            UUID defectId = UUID.randomUUID();
            Category cat = categories.get(random.nextInt(categories.size()));
            Point defectLocation = generateValidDefectLocation(random);
            
            // 90 days spread
            long daysAgo = random.nextInt(90);
            Instant defectTime = now.minus(daysAgo, ChronoUnit.DAYS).minus(random.nextInt(24), ChronoUnit.HOURS);

            int clusterSize;
            double roll = random.nextDouble();
            if (roll < 0.6) {
                clusterSize = 1; // singleton
            } else if (roll < 0.9) {
                clusterSize = 2 + random.nextInt(4); // small cluster 2-5
            } else {
                clusterSize = 6 + random.nextInt(10); // large cluster 6-15
            }

            for (int i = 0; i < clusterSize && generatedCount < targetReports; i++) {
                double accuracy = 5.0 + random.nextDouble() * 45.0; // 5 to 50m
                
                double[] noise;
                while(true) {
                    noise = addNoise(defectLocation.getY(), defectLocation.getX(), accuracy / 2.0, random);
                    if (wardRepo.findIdContaining(noise[0], noise[1]).isPresent()) {
                        break;
                    }
                }
                
                // Spread reports for this defect over up to 5 days
                Instant reportTime = defectTime.plus(random.nextInt(120), ChronoUnit.HOURS);
                if (reportTime.isAfter(now)) {
                    reportTime = now;
                }

                UUID reporterId = null;
                String deviceId = "device-" + random.nextInt(1000);

                reportsToIngest.add(new GeneratedReport(
                    defectId, cat.getCode(), noise[0], noise[1], accuracy, reportTime, reporterId, deviceId
                ));
                generatedCount++;
            }
        }

        // Sort chronologically
        reportsToIngest.sort(Comparator.comparing(GeneratedReport::simulatedTime));

        log.info("Ingesting {} simulated reports sequentially...", reportsToIngest.size());
        
        List<String> labelLines = new ArrayList<>();
        labelLines.add("report_id,defect_id,simulated_time");

        for (int i = 0; i < reportsToIngest.size(); i++) {
            GeneratedReport r = reportsToIngest.get(i);
            
            IngestReportCommand cmd = new IngestReportCommand(
                r.categoryCode(), r.lat(), r.lng(), r.accuracy(), false,
                "Generated report for " + r.categoryCode(), null, null,
                // A URL that actually resolves. The previous value,
                // http://example.com/photo.jpg, 404s -- so every photo on every
                // issue page in a seeded environment rendered as a broken image
                // and the whole application looked broken to anybody running it
                // without a Cloudinary account. The frontend now degrades to a
                // "Photo unavailable" panel rather than a broken-image icon, but
                // the seed corpus should not be exercising that path by default.
                SEED_PHOTO_URL, null, r.reporterId(), r.deviceId()
            );

            ClusterOutcome outcome = clusteringService.ingest(cmd);
            
            // Retroactively fix the timestamps: ingest stamps rows with the
            // clock's current instant, and the corpus needs them spread back
            // over a realistic window.
            jdbcTemplate.update(
                "UPDATE reports SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(r.simulatedTime()), outcome.reportId()
            );

            // Update issue basic timestamps
            jdbcTemplate.update(
                "UPDATE issues SET first_reported_at = LEAST(first_reported_at, ?), " +
                "last_reported_at = GREATEST(last_reported_at, ?), " +
                "created_at = LEAST(created_at, ?), " +
                "updated_at = GREATEST(updated_at, ?) WHERE id = ?",
                java.sql.Timestamp.from(r.simulatedTime()),
                java.sql.Timestamp.from(r.simulatedTime()),
                java.sql.Timestamp.from(r.simulatedTime()),
                java.sql.Timestamp.from(r.simulatedTime()),
                outcome.issueId()
            );

            labelLines.add(outcome.reportId() + "," + r.defectId() + "," + r.simulatedTime());
            
            if ((i + 1) % 100 == 0) {
                log.info("Ingested {} / {} reports...", i + 1, reportsToIngest.size());
            }
        }

        // Write labels file
        try (PrintWriter pw = new PrintWriter(new FileWriter(props.labelsFilePath()))) {
            for (String line : labelLines) {
                pw.println(line);
            }
        }
        log.info("Wrote ground truth labels to {}", props.labelsFilePath());

        // Settle a share of the older issues into RESOLVED, and a share of
        // those onward into CLOSED.
        //
        // These two statements write `status` directly, which the standing rule
        // reserves to IssueStatusService. They stay direct for one reason the
        // service cannot cover: they backdate `resolved_at` and `closed_at` by
        // weeks, and the service takes its timestamps from the injected clock.
        // Everything reachable without backdating goes through the real service
        // instead -- see simulateWorkInProgress below.
        //
        // The selection hashes the issue id rather than calling `random()`.
        //
        // To be clear about what that does and does not buy: it is NOT
        // reproducibility across runs. The ids are random UUIDs, so the hash is
        // a deterministic function of a non-deterministic input -- see the note
        // on the clock field above. What it buys is that the split is stable
        // for a given corpus, so re-running these statements against the same
        // database is idempotent, and that the two predicates below can be made
        // genuinely independent of one another by hashing different bytes,
        // which is what `random()` per-row could not express.
        log.info("Applying simulated statuses to older issues...");
        jdbcTemplate.update("""
            UPDATE issues SET
                status = 'RESOLVED',
                resolved_at = first_reported_at + make_interval(hours => 48),
                updated_at  = first_reported_at + make_interval(hours => 48)
            WHERE first_reported_at < now() - interval '14 days'
              AND ('x' || substr(md5(id::text), 1, 8))::bit(32)::bigint % 100 < 60
        """);

        // Only part of the resolved set closes. The rest stay RESOLVED, which
        // is a state a citizen can still act on -- and an earlier version
        // closed every issue whose resolved_at was more than seven days old,
        // which at a 90-day corpus meant all of them: RESOLVED never appeared
        // in the seeded data at all.
        jdbcTemplate.update("""
            UPDATE issues SET
                status = 'CLOSED',
                closed_at = resolved_at + make_interval(days => 7)
            WHERE status = 'RESOLVED'
              AND resolved_at < now() - interval '7 days'
              -- A DIFFERENT slice of the digest to the one above. Reusing
              -- substr(...,1,8) made the two predicates the same draw on the
              -- same key, so every issue that resolved also closed and
              -- RESOLVED stayed empty -- the exact bug this split was added to
              -- fix, reintroduced by hashing the same bytes twice.
              AND ('x' || substr(md5(id::text), 9, 8))::bit(32)::bigint % 100 < 65
        """);

        simulateWorkInProgress(random);

        log.info("Seed Data Generation complete.");
    }

    /**
     * Walks a slice of the still-NEW issues into the middle of the lifecycle.
     *
     * <p>WHY THIS EXISTS. The two UPDATE statements above take issues from NEW
     * to RESOLVED to CLOSED, and nothing produced the six statuses in between.
     * At a corpus of 2,000 reports that was easy to miss -- there was so much
     * to scroll that variety was assumed. At 250 it is the first thing anybody
     * notices: a queue containing nothing but NEW and CLOSED, an issue detail
     * page whose history has exactly one row, and a staff work view that can
     * only ever offer "acknowledge". Three of the screens this project is
     * judged on had no data that exercised them.
     *
     * <p>WHY IT GOES THROUGH THE REAL SERVICE. Those two UPDATEs write
     * {@code status} directly, which the project's own standing rule forbids
     * everywhere else -- {@code IssueStatusService.transition} is the single
     * writer, and {@code Issue.setStatus} is package-private to enforce it.
     * Extending that shortcut would have been three more UPDATE statements and
     * would have produced a corpus that cannot be trusted: no history rows, no
     * {@code acknowledged_at}, no assignee, and states the transition table
     * would have refused. Driving {@link IssueLifecycleService} instead means
     * every issue in the corpus arrived at its status by a route the policy
     * actually permits, and carries the audit trail to prove it.
     *
     * <p>The existing RESOLVED/CLOSED UPDATEs are deliberately left alone.
     * They backdate {@code resolved_at} and {@code closed_at} by weeks, and
     * the service takes its timestamps from the injected clock, so it cannot
     * express them. That is a real limitation and worth stating plainly rather
     * than working around.
     *
     * <p>The actor is the administrator, which passes SAME_DEPARTMENT and
     * IS_ASSIGNEE by rule. The one guard it cannot satisfy on its own is
     * ASSIGNEE_IN_SAME_DEPARTMENT, so each issue is assigned to a real member
     * of its own department -- crew where one exists, otherwise the
     * department head, since four of the seeded departments have a supervisor
     * and no crew.
     */
    private void simulateWorkInProgress(Random random) {
        UUID adminId = jdbcTemplate.query(
                "SELECT id FROM users WHERE role = 'ADMIN' ORDER BY email LIMIT 1",
                rs -> rs.next() ? (UUID) rs.getObject("id") : null);
        if (adminId == null) {
            log.warn("No ADMIN user found; skipping lifecycle simulation.");
            return;
        }
        Actor admin = new Actor(adminId, Role.ADMIN, null, null);

        // One assignee per department: crew first, department head otherwise.
        Map<UUID, UUID> assigneeByDept = new HashMap<>();
        jdbcTemplate.query(
                """
                SELECT department_id, id, role FROM users
                WHERE department_id IS NOT NULL
                ORDER BY department_id, CASE role WHEN 'STAFF' THEN 0 ELSE 1 END, email
                """,
                rs -> {
                    assigneeByDept.putIfAbsent(
                            (UUID) rs.getObject("department_id"), (UUID) rs.getObject("id"));
                });

        List<UUID> candidates = jdbcTemplate.queryForList(
                "SELECT id FROM issues WHERE status = 'NEW' ORDER BY first_reported_at DESC, id",
                UUID.class);
        Collections.shuffle(candidates, random);

        // Proportions of the NEW pool, chosen so every screen has something to
        // show: roughly a fifth acknowledged, a fifth assigned but not started,
        // a fifth in progress, a tenth awaiting citizen verification, a few
        // rejected, and the rest left untouched so NEW is still the largest
        // state -- which is what an under-resourced municipality looks like.
        int n = candidates.size();
        int acknowledged = (int) (n * 0.18);
        int assigned     = (int) (n * 0.15);
        int inProgress   = (int) (n * 0.15);
        int pendingVerif = (int) (n * 0.10);
        int rejected     = (int) (n * 0.05);

        int i = 0, ok = 0, refused = 0;
        for (int stage = 0; stage < 5; stage++) {
            int count = switch (stage) {
                case 0 -> acknowledged;
                case 1 -> assigned;
                case 2 -> inProgress;
                case 3 -> pendingVerif;
                default -> rejected;
            };
            for (int k = 0; k < count && i < n; k++, i++) {
                UUID issueId = candidates.get(i);
                try {
                    if (stage == 4) {
                        lifecycleService.reject(issueId, admin,
                                "Not a municipal responsibility: this location is on private "
                                + "land and the owner has been notified directly.");
                        ok++;
                        continue;
                    }

                    lifecycleService.acknowledge(issueId, admin,
                            "Logged by the department and queued for a crew.");
                    if (stage == 0) { ok++; continue; }

                    UUID deptId = jdbcTemplate.queryForObject(
                            "SELECT department_id FROM issues WHERE id = ?", UUID.class, issueId);
                    UUID assigneeId = assigneeByDept.get(deptId);
                    if (assigneeId == null) { continue; }

                    lifecycleService.assign(issueId, admin, assigneeId,
                            "Assigned to the crew covering this ward.");
                    if (stage == 1) { ok++; continue; }

                    lifecycleService.start(issueId, admin, "Crew on site, work underway.");
                    if (stage == 2) { ok++; continue; }

                    lifecycleService.submitForVerification(issueId, admin, SEED_PHOTO_URL,
                            "Repair completed and photographed on site; awaiting confirmation "
                            + "from the people who reported it.");
                    ok++;
                } catch (RuntimeException e) {
                    // A refusal is information, not a failure to swallow
                    // quietly: it means the transition table and this
                    // simulation disagree, and the table is the authority.
                    refused++;
                    log.warn("Lifecycle simulation refused on issue {}: {}",
                            issueId, e.getMessage());
                }
            }
        }

        // Everything above happened at the clock's instant, so the whole slice
        // reads as having been actioned in the same second -- which makes the
        // "acknowledged 3 minutes after it was reported" figures on the issue
        // page nonsense. Shift each issue's action timestamps, and the history
        // rows with them, into the window between the report and now.
        jdbcTemplate.update("""
            UPDATE issues SET
                acknowledged_at = first_reported_at + make_interval(hours => 6),
                assigned_at     = CASE WHEN assigned_at IS NOT NULL
                                       THEN first_reported_at + make_interval(hours => 18)
                                  END,
                updated_at      = first_reported_at + make_interval(hours => 24)
            WHERE acknowledged_at IS NOT NULL
              AND status NOT IN ('RESOLVED', 'CLOSED')
        """);
        // Space each issue's history rows six hours apart, in the order they
        // were actually written. The obvious version -- an arbitrary offset per
        // row -- can land ACKNOWLEDGED after IN_PROGRESS, and the issue page
        // renders history in timestamp order, so the audit trail would read as
        // though the work started before anyone had looked at it. `row_number`
        // over the row's own id preserves the transition sequence, because the
        // rows were inserted in that sequence. The cast to int is required:
        // `make_interval` has no bigint overload and row_number returns one.
        jdbcTemplate.update("""
            UPDATE issue_status_history h
               SET created_at = i.first_reported_at
                                + make_interval(hours => (o.rn * 6)::int)
              FROM issues i,
                   (SELECT id, row_number() OVER (PARTITION BY issue_id ORDER BY id) AS rn
                      FROM issue_status_history) o
             WHERE o.id = h.id
               AND i.id = h.issue_id
               AND h.created_at > i.first_reported_at
        """);

        log.info("Lifecycle simulation: {} issues advanced, {} refused by the transition table.",
                ok, refused);
    }

    private Point generateValidDefectLocation(Random random) {
        while (true) {
            // Ludhiana Grid: longitude 75.78 .. 75.94, latitude 30.84 .. 30.96
            double lng = 75.78 + random.nextDouble() * (75.94 - 75.78);
            double lat = 30.84 + random.nextDouble() * (30.96 - 30.84);
            
            if (wardRepo.findIdContaining(lat, lng).isPresent()) {
                return GeoFactory.point(lat, lng);
            }
        }
    }

    private double[] addNoise(double lat, double lng, double noiseMeters, Random random) {
        double dLat = (random.nextGaussian() * noiseMeters) / 111000.0;
        double dLng = (random.nextGaussian() * noiseMeters) / 95000.0;
        return new double[]{lat + dLat, lng + dLng};
    }
}
