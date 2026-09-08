package com.civictrack.issue.dto;

import com.civictrack.issue.IssueRepository.QueueRow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the staff work queue.
 *
 * <p>Wider than {@link IssueDto} on purpose: blueprint 3.14 specifies the
 * columns a field worker reads to decide what to do next -- category, ward and
 * landmark among them -- and none of those are on the issue row. The category
 * and ward names are joins; the landmark belongs to the issue's earliest
 * report, because an issue has as many landmarks as it has reporters and the
 * useful one is what the person who found the problem called the place.
 *
 * <p>This record carries {@code assignedTo}, which is exactly why it is in the
 * staff package and not in
 * {@link com.civictrack.publicapi.dto}. Nothing public may return it.
 *
 * <p>{@code effectiveDeadline} is precomputed rather than left to the client:
 * the deadline that counts is {@code dueAt + pausedSeconds}, and a countdown
 * component that rederived it would be a second definition of the SLA clock
 * living in TypeScript.
 */
public record QueueRowDto(
        UUID id,
        String publicRef,
        String categoryCode,
        String categoryName,
        UUID wardId,
        String wardName,
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
        Instant dueAt,
        long pausedSeconds,
        Instant effectiveDeadline,
        int escalationLevel,
        UUID assignedTo,
        String landmark
) {
    public static QueueRowDto from(QueueRow r) {
        return new QueueRowDto(
                r.getId(), r.getPublicRef(),
                r.getCategoryCode(), r.getCategoryName(),
                r.getWardId(), r.getWardName(), r.getDepartmentId(),
                r.getStatus(), r.getPriority(), r.getPriorityScore(),
                r.getLat(), r.getLng(),
                r.getReportCount(), r.getDistinctReporterCount(),
                r.getNeedsReview(), r.getReviewReason(),
                r.getFirstReportedAt(), r.getDueAt(), r.getPausedSeconds(),
                r.getDueAt().plusSeconds(r.getPausedSeconds()),
                r.getEscalationLevel(), r.getAssignedTo(), r.getLandmark());
    }
}
