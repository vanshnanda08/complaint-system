package com.civictrack.me;

import com.civictrack.issue.IssueRepository;
import com.civictrack.publicapi.dto.PublicIssueDto;
import com.civictrack.report.Report;
import com.civictrack.report.ReportRepository;
import com.civictrack.user.Actor;
import com.civictrack.user.auth.Actors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The caller's own reports.
 *
 * <p>The reporter id comes from the validated token and from nowhere else.
 * There is no {@code ?reporterId=} and there must never be one, for the reason
 * {@link com.civictrack.issue.api.StaffQueueController} spells out about
 * {@code ?departmentId=}: an authorisation decision expressed as a query
 * parameter is an authorisation decision the first curious user overrides.
 *
 * <p>Anonymous reports are not reachable here even by their own author.
 * Reports carry a {@code device_id} but a device is not an identity the server
 * will authenticate, and treating one as a credential would let anybody who
 * learned a device id read that phone's history. This is the concrete cost of
 * anonymous reporting that blueprint 3.2 requires the composer to state up
 * front, rather than a gap to paper over.
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MyReportsController {

    private static final int MAX_PAGE = 100;

    private final ReportRepository reports;
    private final IssueRepository issues;

    @GetMapping("/reports")
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public MyReportsPage list(@RequestParam(defaultValue = "50") int limit,
                              @RequestParam(defaultValue = "0") int offset,
                              @AuthenticationPrincipal Jwt jwt) {
        Actor actor = Actors.from(jwt);
        int boundedLimit = Math.min(Math.max(limit, 1), MAX_PAGE);
        int boundedOffset = Math.max(offset, 0);

        List<Report> mine = reports.findByReporter(actor.id(), boundedLimit, boundedOffset);

        // One batched lookup for every issue on the page, rather than one per
        // row. Several reports commonly share an issue -- that is the product
        // working -- so the set is usually smaller than the page.
        Set<UUID> issueIds = new LinkedHashSet<>(mine.stream().map(Report::getIssueId).toList());
        Map<UUID, PublicIssueDto> byId = issueIds.isEmpty() ? Map.of()
                : issues.findPublicByIdIn(issueIds).stream()
                        .map(PublicIssueDto::from)
                        .collect(java.util.stream.Collectors.toMap(
                                PublicIssueDto::id, Function.identity()));

        return new MyReportsPage(
                mine.stream().map(r -> MyReportDto.from(r, byId.get(r.getIssueId()))).toList(),
                reports.countByReporterId(actor.id()),
                boundedLimit, boundedOffset);
    }

    public record MyReportsPage(List<MyReportDto> items, long total, int limit, int offset) {
    }
}
