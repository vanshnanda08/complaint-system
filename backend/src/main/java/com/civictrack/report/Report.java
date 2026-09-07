package com.civictrack.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable citizen observation. Evidence, not work.
 *
 * <p>Never edited and never deleted. A moderator may move a report between
 * issues by changing {@code issueId}, but the observation itself -- its point,
 * its accuracy, its photo, its reporter, its timestamp -- is fixed at capture.
 * That immutability is what makes report count a defensible priority signal and
 * what makes split and merge non-destructive.
 */
@Entity
@Table(name = "reports")
@Getter
@Setter
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "issue_id", nullable = false)
    private UUID issueId;

    /** Null for an anonymous report, in which case deviceId carries identity. */
    @Column(name = "reporter_id")
    private UUID reporterId;

    @Column(name = "device_id", length = 64)
    private String deviceId;

    @Column(name = "category_code", nullable = false, length = 40)
    private String categoryCode;

    @Column(name = "location", nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point location;

    @Column(name = "gps_accuracy_m", nullable = false)
    private double gpsAccuracyM;

    @Column(name = "manual_pin", nullable = false)
    private boolean manualPin;

    @Column(name = "address_text", length = 300)
    private String addressText;

    @Column(name = "landmark", length = 200)
    private String landmark;

    @Column(name = "description")
    private String description;

    @Column(name = "photo_url", nullable = false)
    private String photoUrl;

    @Column(name = "photo_hash", length = 64)
    private String photoHash;

    // ---- clustering audit trail ---------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "cluster_decision", nullable = false, length = 24)
    private ClusterDecision clusterDecision;

    /** Distance to the chosen candidate's centroid, as re-read under lock (DD-003). */
    @Column(name = "cluster_distance_m")
    private Double clusterDistanceM;

    @Column(name = "effective_radius_m")
    private Double effectiveRadiusM;

    /**
     * DD-001. The extent the target cluster would have had if this report had
     * merged into it. Recorded whether or not the merge happened, so the
     * extent-cap ablation runs over the audit trail rather than by re-running
     * the pipeline.
     */
    @Column(name = "projected_extent_m")
    private Double projectedExtentM;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
