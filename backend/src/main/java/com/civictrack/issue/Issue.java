package com.civictrack.issue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.locationtech.jts.geom.Point;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A mutable municipal work item aggregating one or more immutable reports.
 *
 * <p>Associations are held as raw identifiers rather than {@code @ManyToOne}
 * graphs. With {@code open-in-view=false} a lazy association touched during
 * serialisation throws, and none of the clustering paths need the related
 * entity anyway -- they need its id. Fewer joins in the hot path, and no
 * LazyInitializationException class of bug.
 */
@Entity
@Table(name = "issues")
@Getter
@Setter
public class Issue {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "public_ref", nullable = false, length = 20, updatable = false)
    private String publicRef;

    @Column(name = "category_code", nullable = false, length = 40)
    private String categoryCode;

    @Column(name = "ward_id", nullable = false)
    private UUID wardId;

    @Column(name = "department_id")
    private UUID departmentId;

    /**
     * Package-private setter. Standing rule 5: status is written by
     * {@link IssueStatusService#transition} and by nothing else, which is
     * enforced here by visibility rather than by convention. Lombok's
     * generated setter is suppressed for this field.
     */
    @Enumerated(EnumType.STRING)
    @Setter(lombok.AccessLevel.PACKAGE)
    @Column(name = "status", nullable = false, length = 24)
    private IssueStatus status = IssueStatus.NEW;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 12)
    private Priority priority = Priority.MEDIUM;

    @Column(name = "priority_score", nullable = false, precision = 8, scale = 2)
    private BigDecimal priorityScore = BigDecimal.ZERO;

    // ---- derived cluster geometry -------------------------------------
    // sum_w / sum_wx / sum_wy make the accuracy-weighted centroid an O(1)
    // update per arriving report instead of a rescan of member reports.

    @Column(name = "centroid", nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point centroid;

    @Column(name = "sum_w", nullable = false)
    private double sumW;

    @Column(name = "sum_wx", nullable = false)
    private double sumWx;

    @Column(name = "sum_wy", nullable = false)
    private double sumWy;

    /** DD-001. Geodesic metres from the centroid to the furthest member report. */
    @Column(name = "max_member_dist_m", nullable = false)
    private double maxMemberDistM;

    @Column(name = "report_count", nullable = false)
    private int reportCount;

    @Column(name = "distinct_reporter_count", nullable = false)
    private int distinctReporterCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "cluster_confidence", nullable = false, length = 10)
    private ClusterConfidence clusterConfidence = ClusterConfidence.HIGH;

    @Column(name = "needs_review", nullable = false)
    private boolean needsReview;

    @Column(name = "review_reason", length = 40)
    private String reviewReason;

    // ---- SLA clock ----------------------------------------------------

    @Column(name = "first_reported_at", nullable = false)
    private Instant firstReportedAt;

    @Column(name = "last_reported_at", nullable = false)
    private Instant lastReportedAt;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "clock_paused_at")
    private Instant clockPausedAt;

    @Column(name = "paused_seconds", nullable = false)
    private long pausedSeconds;

    @Column(name = "escalation_level", nullable = false)
    private int escalationLevel;

    @Column(name = "last_escalated_at")
    private Instant lastEscalatedAt;

    // ---- ownership and outcome ----------------------------------------
    // Written only by IssueStatusService, alongside the status they belong
    // to. Keeping them next to the transition that sets them is what stops
    // an issue reaching RESOLVED with a null resolved_at, which no amount
    // of nullable columns would have caught.

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "resolution_note")
    private String resolutionNote;

    @Column(name = "resolution_photo_url")
    private String resolutionPhotoUrl;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "rejected_reason")
    private String rejectedReason;

    /** DD-006. Set when an issue resolves through the timeout with no votes. */
    @Column(name = "resolved_without_verification", nullable = false)
    private boolean resolvedWithoutVerification;

    @Column(name = "reopen_count", nullable = false)
    private int reopenCount;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** Marks the issue for supervisor review, recording why. */
    public void flagForReview(String reason) {
        this.needsReview = true;
        this.reviewReason = reason;
        this.clusterConfidence = ClusterConfidence.LOW;
    }
}
