package com.civictrack.publicapi.dto;

import com.civictrack.clustering.CentroidMath;
import com.civictrack.issue.IssueRepository.PublicIssueRow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One issue as an anonymous caller may see it.
 *
 * <p>A separate record from {@link com.civictrack.issue.dto.IssueDto} rather
 * than a subset of it, and that is the whole point. {@code IssueDto} carries
 * {@code assignedTo} -- a municipal employee's user id -- because the staff
 * API is entitled to it. If the public surface reused that record and filtered
 * fields at serialisation time, the next field added to the staff view would
 * become public by default. Here the default runs the other way: a field
 * reaches the public only if somebody writes it into this record.
 *
 * <p>Never present, and never to be added: {@code assignedTo}, {@code resolvedBy},
 * any reporter id, any device id, any {@code actorId}, and the raw centroid
 * accumulators {@code sumW}/{@code sumWx}/{@code sumWy}. The accumulators are
 * excluded on a different ground from the identifiers -- not privacy but
 * authority. They are internal state of an O(1) update, and publishing them
 * invites a client to recompute a centroid the server is the only authority
 * on. What the cluster inspector actually needs from them is the positional
 * uncertainty, so that is computed here and the sums stay behind.
 */
public record PublicIssueDto(
        UUID id,
        String publicRef,
        String categoryCode,
        String categoryName,
        UUID wardId,
        int wardNumber,
        String wardName,
        UUID departmentId,
        String departmentName,
        String status,
        String priority,
        BigDecimal priorityScore,
        double lat,
        double lng,
        int reportCount,
        int distinctReporterCount,
        boolean needsReview,
        String reviewReason,
        Instant firstReportedAt,
        Instant lastReportedAt,
        Instant dueAt,
        long pausedSeconds,
        Instant effectiveDeadline,
        int escalationLevel,
        Instant resolvedAt,
        int reopenCount,
        String resolutionNote,
        String resolutionPhotoUrl,
        boolean resolvedWithoutVerification,
        int mergeRadiusM,
        double clusterExtentM,
        double clusterExtentCapM,
        double positionalUncertaintyM
) {
    public static PublicIssueDto from(PublicIssueRow r) {
        return new PublicIssueDto(
                r.getId(), r.getPublicRef(),
                r.getCategoryCode(), r.getCategoryName(),
                r.getWardId(), r.getWardNumber(), r.getWardName(),
                r.getDepartmentId(), r.getDepartmentName(),
                r.getStatus(), r.getPriority(), r.getPriorityScore(),
                r.getLat(), r.getLng(),
                r.getReportCount(), r.getDistinctReporterCount(),
                r.getNeedsReview(), r.getReviewReason(),
                r.getFirstReportedAt(), r.getLastReportedAt(),
                r.getDueAt(), r.getPausedSeconds(),
                // The deadline as it is actually judged. SlaService.effectiveDeadline
                // is the same arithmetic; it is done here rather than by calling that
                // service because this path never loads an Issue entity.
                r.getDueAt().plusSeconds(r.getPausedSeconds()),
                r.getEscalationLevel(),
                r.getResolvedAt(), r.getReopenCount(),
                r.getResolutionNote(), r.getResolutionPhotoUrl(),
                r.getResolvedWithoutVerification(),
                r.getMergeRadiusM(),
                round1(r.getMaxMemberDistM()),
                round1(r.getMaxExtentMultiplier().doubleValue() * r.getMergeRadiusM()),
                // Blueprint 3.7's header line: sigma = 1/sqrt(sum of inverse-variance
                // weights. Two reports at 10 m give about 7 m, twenty give about 2 m,
                // which is the adaptive-radius argument made visible.
                round1(CentroidMath.sigmaIssue(r.getSumW())));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
