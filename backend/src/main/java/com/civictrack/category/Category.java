package com.civictrack.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Category configuration. Standing rule 1: every tunable number in the
 * clustering and SLA engines is a column on this entity, so changing a merge
 * radius is an UPDATE rather than a redeploy, and "how did you choose 25 m?"
 * has an answer that is configuration with a rationale.
 */
@Entity
@Table(name = "categories")
@Getter
@Setter
public class Category {

    @Id
    @Column(name = "code", length = 40)
    private String code;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    /** Base merge radius in metres, tracking the physical extent of the defect. */
    @Column(name = "merge_radius_m", nullable = false)
    private int mergeRadiusM;

    @Column(name = "default_sla_hours", nullable = false)
    private int defaultSlaHours;

    @Column(name = "severity_weight", nullable = false)
    private int severityWeight;

    @Column(name = "reopen_window_days", nullable = false)
    private int reopenWindowDays;

    /** DD-001. Cluster extent is capped at this multiple of mergeRadiusM. */
    @Column(name = "max_extent_multiplier", nullable = false, precision = 4, scale = 2)
    private BigDecimal maxExtentMultiplier;

    /** DD-002. What the low-confidence band does for this category. */
    @Enumerated(EnumType.STRING)
    @Column(name = "low_conf_action", nullable = false, length = 12)
    private LowConfAction lowConfAction;

    @Column(name = "is_mobile_target", nullable = false)
    private boolean mobileTarget;

    @Column(name = "department_id")
    private UUID departmentId;

    @Column(name = "active", nullable = false)
    private boolean active;

    /** The extent cap in metres, i.e. multiplier x base radius (DD-001). */
    public double extentCapM() {
        return maxExtentMultiplier.doubleValue() * mergeRadiusM;
    }
}
