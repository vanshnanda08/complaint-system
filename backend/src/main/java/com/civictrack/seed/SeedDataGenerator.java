package com.civictrack.seed;

import com.civictrack.category.Category;
import com.civictrack.category.CategoryRepository;
import com.civictrack.clustering.ClusterOutcome;
import com.civictrack.clustering.ClusteringService;
import com.civictrack.clustering.IngestReportCommand;
import com.civictrack.common.geo.GeoFactory;
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
    private final JdbcTemplate jdbcTemplate;

    /**
     * Standing rule: time comes from the injected clock, never from
     * {@code Instant.now()}. It matters here even though this is a generator
     * rather than a service -- the corpus is spread backwards over a window
     * from "now", and the ground-truth labels written alongside it are only
     * reproducible if that origin is reproducible. With a fixed random seed and
     * a fixed clock the same corpus comes out twice, which is what the phase-8
     * evaluation needs in order to compare runs.
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

        // Make the dashboard look alive by randomly resolving some older issues
        log.info("Applying simulated statuses to older issues...");
        jdbcTemplate.update("""
            UPDATE issues SET 
                status = 'RESOLVED', 
                resolved_at = first_reported_at + make_interval(hours => 48),
                updated_at = first_reported_at + make_interval(hours => 48)
            WHERE first_reported_at < now() - interval '14 days' 
            AND random() < 0.6
        """);

        jdbcTemplate.update("""
            UPDATE issues SET 
                status = 'CLOSED',
                closed_at = resolved_at + make_interval(days => 7)
            WHERE status = 'RESOLVED' 
            AND resolved_at < now() - interval '7 days'
        """);

        log.info("Seed Data Generation complete.");
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
