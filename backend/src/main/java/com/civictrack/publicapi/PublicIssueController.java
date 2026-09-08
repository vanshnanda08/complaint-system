package com.civictrack.publicapi;

import com.civictrack.issue.IssueNotFoundException;
import com.civictrack.issue.IssueRepository;
import com.civictrack.issue.IssueStatusHistoryRepository;
import com.civictrack.publicapi.dto.BboxResultDto;
import com.civictrack.publicapi.dto.PublicHistoryDto;
import com.civictrack.publicapi.dto.PublicIssueDto;
import com.civictrack.publicapi.dto.PublicIssuePage;
import com.civictrack.publicapi.dto.PublicReportDto;
import com.civictrack.report.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The public read surface: everything the city knows about its own open work,
 * to anybody, with no account.
 *
 * <p>This controller has no {@code @PreAuthorize} anywhere and that is
 * deliberate rather than forgotten. "Everything here is public, no login" is
 * the product, and the security chain permits {@code GET /api/v1/public/**}
 * for exactly that reason. What protects the citizen is not access control on
 * these paths but the shape of
 * {@link com.civictrack.publicapi.dto.PublicIssueDto} -- no staff identity, no
 * reporter identity -- which is enforced by the record having no such
 * components at all.
 *
 * <p>Note what is <em>not</em> here: {@code GET /api/v1/issues/{id}}, the
 * staff view, still exists separately behind {@code @issueGuard}. Two
 * endpoints for one entity is not duplication; it is two different answers to
 * "who is asking", and collapsing them into one endpoint with a conditional
 * projection is how a field ends up public because of a branch nobody re-read.
 */
@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
public class PublicIssueController {

    /** Blueprint 3.4. Beyond this the map asks the user to zoom rather than lying. */
    private static final int BBOX_CAP = 500;

    private static final int MAX_PAGE = 200;

    private final IssueRepository issues;
    private final ReportRepository reports;
    private final IssueStatusHistoryRepository history;

    @GetMapping("/issues")
    @Transactional(readOnly = true)
    public PublicIssuePage list(@RequestParam(required = false) String status,
                                @RequestParam(required = false) String category,
                                @RequestParam(required = false) UUID ward,
                                @RequestParam(required = false) String sort,
                                @RequestParam(defaultValue = "50") int limit,
                                @RequestParam(defaultValue = "0") int offset) {
        int boundedLimit = Math.min(Math.max(limit, 1), MAX_PAGE);
        int boundedOffset = Math.max(offset, 0);

        List<PublicIssueDto> items = issues
                .findPublicPage(normalise(status), normalise(category), ward,
                                normalise(sort), boundedLimit, boundedOffset)
                .stream().map(PublicIssueDto::from).toList();

        return new PublicIssuePage(items,
                issues.countPublic(normalise(status), normalise(category), ward),
                boundedLimit, boundedOffset);
    }

    @GetMapping("/issues/bbox")
    @Transactional(readOnly = true)
    public BboxResultDto bbox(@RequestParam double south,
                              @RequestParam double west,
                              @RequestParam double north,
                              @RequestParam double east,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) String category) {
        // One extra row is fetched so that "capped" reports whether anything
        // was actually left out, rather than guessing from a full page --
        // exactly 500 matching issues is not a truncated result and must not
        // be reported as one.
        List<PublicIssueDto> found = issues
                .findPublicInBbox(south, west, north, east,
                                  normalise(status), normalise(category), BBOX_CAP + 1)
                .stream().map(PublicIssueDto::from).toList();

        boolean capped = found.size() > BBOX_CAP;
        return new BboxResultDto(capped ? found.subList(0, BBOX_CAP) : found, BBOX_CAP, capped);
    }

    @GetMapping("/issues/{id}")
    @Transactional(readOnly = true)
    public PublicIssueDto get(@PathVariable UUID id) {
        return issues.findPublicById(id).map(PublicIssueDto::from)
                .orElseThrow(() -> new IssueNotFoundException(id));
    }

    /**
     * Resolves a ticket reference to its issue.
     *
     * <p>The report result screen is keyed by the reference, because the
     * reference is the artifact the citizen keeps -- read aloud over a phone,
     * pasted into WhatsApp, quoted back at a counter. Without this endpoint
     * that screen renders only from router state and breaks on a refresh, a
     * shared link, or the back button, which is the one screen in the product
     * where that is least acceptable.
     */
    @GetMapping("/issues/by-ref/{publicRef}")
    @Transactional(readOnly = true)
    public PublicIssueDto getByRef(@PathVariable String publicRef) {
        return issues.findPublicByRef(publicRef).map(PublicIssueDto::from)
                .orElseThrow(() -> new IssueNotFoundException(publicRef));
    }

    /**
     * The member reports, oldest first, each with the decision that put it
     * here.
     *
     * <p>Ordering is load-bearing: the sequence number the side panel prints
     * is the report's position in the cluster's history, so it has to be
     * assigned from creation order rather than from whatever order the
     * database felt like returning.
     */
    @GetMapping("/issues/{id}/reports")
    @Transactional(readOnly = true)
    public List<PublicReportDto> reports(@PathVariable UUID id) {
        requireIssue(id);
        List<PublicReportDto> out = new ArrayList<>();
        int seq = 1;
        for (var report : reports.findByIssueIdOrderByCreatedAtAsc(id)) {
            out.add(PublicReportDto.from(report, seq++));
        }
        return out;
    }

    @GetMapping("/issues/{id}/history")
    @Transactional(readOnly = true)
    public List<PublicHistoryDto> history(@PathVariable UUID id) {
        requireIssue(id);
        return history.findByIssueIdOrderByCreatedAtAsc(id)
                .stream().map(PublicHistoryDto::from).toList();
    }

    /**
     * 404 for an unknown issue on the sub-resources, rather than an empty
     * list. An empty list is a true statement about an issue with no reports,
     * and there is no such issue -- so returning one for an id that does not
     * exist would make "no reports" and "no such ticket" indistinguishable to
     * the screen that has to word the difference.
     */
    private void requireIssue(UUID id) {
        if (!issues.existsById(id)) {
            throw new IssueNotFoundException(id);
        }
    }

    /** Blank query parameters are absent parameters, not filters matching "". */
    private static String normalise(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
