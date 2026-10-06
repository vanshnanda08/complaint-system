package com.civictrack.moderation;

import com.civictrack.issue.IssueRepository;
import com.civictrack.moderation.dto.ReviewItemDto;
import com.civictrack.moderation.dto.ReviewPageDto;
import com.civictrack.report.Report;
import com.civictrack.report.ReportRepository;
import com.civictrack.user.Actor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** The reads behind /supervisor/review. */
@Service
@RequiredArgsConstructor
public class ReviewQueueService {

    private final IssueRepository issues;
    private final ReportRepository reports;

    @Transactional(readOnly = true)
    public ReviewPageDto page(Actor actor, int limit, int offset) {
        // Scope from the token, as the staff queue does: an administrator sees
        // the city, everybody else their department or ward.
        UUID departmentId = actor.isAdmin() ? null : actor.departmentId();
        UUID wardId = actor.isAdmin() ? null : actor.wardId();

        List<IssueRepository.ReviewRow> rows = issues.findReviewRows(departmentId, wardId, limit, offset);
        // One query for every thumbnail on the page, not one per row.
        Map<UUID, List<Report>> members = rows.isEmpty() ? Map.of()
                : reports.findByIssueIdInOrderByCreatedAtAsc(rows.stream().map(IssueRepository.ReviewRow::getId).toList())
                        .stream().collect(Collectors.groupingBy(Report::getIssueId));

        List<ReviewItemDto> items = rows.stream().map(r -> new ReviewItemDto(
                r.getId(), r.getPublicRef(), r.getCategoryCode(), r.getCategoryName(),
                r.getWardId(), r.getWardName(), r.getStatus(), r.getPriority(), r.getReviewReason(),
                r.getLat(), r.getLng(), r.getReportCount(), r.getDistinctReporterCount(),
                Math.round(r.getExtentM() * 10.0) / 10.0, ReviewItemDto.cap(r.getExtentCapM()),
                r.getMergeRadiusM(), r.getFirstReportedAt(), r.getEffectiveDeadline(),
                members.getOrDefault(r.getId(), List.of()).stream()
                        .map(p -> new ReviewItemDto.Point(p.getId(), p.getLocation().getY(),
                                p.getLocation().getX(), p.getGpsAccuracyM(), p.getClusterDecision().name()))
                        .toList()))
                .toList();

        return new ReviewPageDto(items, issues.countReviewRows(departmentId, wardId), limit, offset);
    }
}
