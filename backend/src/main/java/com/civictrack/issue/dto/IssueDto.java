package com.civictrack.issue.dto;

import com.civictrack.issue.Issue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The staff-facing view of an issue.
 *
 * <p>A record with a static factory rather than a mapped type (DD-008), and the
 * factory is where two rules live that a generated mapper would have hidden:
 * the JTS centroid is never serialised -- Jackson emits the whole geometry
 * graph and clients choke on it -- and no reporter identity appears here.
 */
public record IssueDto(
        UUID id,
        String publicRef,
        String categoryCode,
        UUID wardId,
        UUID departmentId,
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
        UUID assignedTo,
        Instant resolvedAt,
        int reopenCount
) {
    public static IssueDto from(Issue issue) {
        return new IssueDto(
                issue.getId(), issue.getPublicRef(), issue.getCategoryCode(),
                issue.getWardId(), issue.getDepartmentId(),
                issue.getStatus().name(), issue.getPriority().name(), issue.getPriorityScore(),
                issue.getCentroid().getY(), issue.getCentroid().getX(),
                issue.getReportCount(), issue.getDistinctReporterCount(),
                issue.isNeedsReview(), issue.getReviewReason(),
                issue.getFirstReportedAt(), issue.getLastReportedAt(), issue.getDueAt(),
                issue.getPausedSeconds(),
                // due_at + paused_seconds: the deadline as it is actually judged.
                // Computed here so no client has to re-derive the SLA clock --
                // a second definition of it in TypeScript is a second definition
                // that can drift. SlaService.effectiveDeadline is the same sum.
                issue.getDueAt().plusSeconds(issue.getPausedSeconds()),
                issue.getEscalationLevel(), issue.getAssignedTo(),
                issue.getResolvedAt(), issue.getReopenCount());
    }
}
