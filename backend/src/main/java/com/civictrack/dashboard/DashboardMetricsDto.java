package com.civictrack.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The public dashboard's aggregate figures (blueprint 3.8, phase 7).
 *
 * <p>Every section is nullable, and null means that section's query failed --
 * the same contract as {@link DashboardSummaryDto#overdueCount()}. It never
 * means "zero" or "nothing to show": an empty section is an empty list, or a
 * figure below {@code sampleSize} that the client presents as "needs N more".
 */
public record DashboardMetricsDto(
        ResolutionTime resolutionTime,
        SlaCompliance slaCompliance,
        List<AgeBucket> backlogAge,
        List<DayCount> reportedVsResolved,
        List<TopCluster> topClusters,
        int minimumSample,
        Instant generatedAt
) {
    /** Hours from first report to resolution. {@code medianHours} is null below the minimum sample. */
    public record Median(String name, Double medianHours, long resolved) {
    }

    public record ResolutionTime(Median overall, List<Median> byDepartment, List<Median> byWard) {
    }

    /** {@code rate} is null below the minimum sample. */
    public record SlaCompliance(long resolved, long onTime, Double rate, List<ComplianceDay> trend) {
    }

    public record ComplianceDay(LocalDate day, long resolved, long onTime) {
    }

    /** {@code label} is for display; the bounds are what it means, in days. */
    public record AgeBucket(String label, int fromDays, Integer toDays, long open) {
    }

    public record DayCount(LocalDate day, long reported, long resolved) {
    }

    public record TopCluster(UUID id, String publicRef, String categoryName, String wardName,
                             String status, int distinctReporterCount, int reportCount) {
    }
}
