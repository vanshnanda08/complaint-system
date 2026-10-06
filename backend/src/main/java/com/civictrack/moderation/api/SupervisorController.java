package com.civictrack.moderation.api;

import com.civictrack.issue.Issue;
import com.civictrack.moderation.ModerationService;
import com.civictrack.moderation.ReviewQueueService;
import com.civictrack.moderation.dto.ClusterGeometryDto;
import com.civictrack.moderation.dto.MergeRequest;
import com.civictrack.moderation.dto.ModerationEntryDto;
import com.civictrack.moderation.dto.ModerationResultDto;
import com.civictrack.moderation.dto.NoteRequest;
import com.civictrack.moderation.dto.RecategoriseRequest;
import com.civictrack.moderation.dto.ReviewPageDto;
import com.civictrack.moderation.dto.SplitPreviewDto;
import com.civictrack.moderation.dto.SplitRequest;
import com.civictrack.user.auth.Actors;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Supervisor moderation (blueprint 3.17, 3.18; phase 7).
 *
 * <p>Two layers on every endpoint, as on the staff API: the role, and
 * {@code @issueGuard} scope on the issue in the path. A merge names a second
 * issue in its body, and the guard is applied to that one too -- otherwise a
 * Roads supervisor could merge a Water issue into their own and take its
 * reports with it.
 *
 * <p>The previews are POSTs because they carry a selection, not because they
 * change anything. Nothing is locked or written by them.
 */
@RestController
@RequestMapping("/api/v1/supervisor")
@RequiredArgsConstructor
public class SupervisorController {

    private static final int MAX_PAGE = 100;

    private final ModerationService moderation;
    private final ReviewQueueService reviewQueue;

    @GetMapping("/review")
    @PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN')")
    public ReviewPageDto review(@RequestParam(defaultValue = "25") int limit,
                                @RequestParam(defaultValue = "0") int offset,
                                @AuthenticationPrincipal Jwt jwt) {
        return reviewQueue.page(Actors.from(jwt), Math.min(Math.max(limit, 1), MAX_PAGE), Math.max(offset, 0));
    }

    @PostMapping("/issues/{id}/confirm")
    @PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN') and @issueGuard.canAct(#id, authentication)")
    public ModerationResultDto confirm(@PathVariable UUID id,
                                       @Valid @RequestBody(required = false) NoteRequest body,
                                       @AuthenticationPrincipal Jwt jwt) {
        return result(moderation.confirm(id, Actors.from(jwt), body == null ? null : body.note()), null);
    }

    @PostMapping("/issues/{id}/split/preview")
    @PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN') and @issueGuard.canAct(#id, authentication)")
    public SplitPreviewDto previewSplit(@PathVariable UUID id, @Valid @RequestBody SplitRequest body) {
        ModerationService.SplitPlan plan = moderation.previewSplit(id, body.reportIds());
        return new SplitPreviewDto(ClusterGeometryDto.from(plan.remaining()), ClusterGeometryDto.from(plan.created()));
    }

    @PostMapping("/issues/{id}/split")
    @PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN') and @issueGuard.canAct(#id, authentication)")
    public ModerationResultDto split(@PathVariable UUID id, @Valid @RequestBody SplitRequest body,
                                     @AuthenticationPrincipal Jwt jwt) {
        ModerationService.SplitResult r = moderation.split(id, body.reportIds(), Actors.from(jwt), body.note());
        return result(r.original(), r.created());
    }

    @PostMapping("/issues/{id}/merge/preview")
    @PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN') and @issueGuard.canAct(#id, authentication)"
            + " and @issueGuard.canAct(#body.sourceIssueId(), authentication)")
    public ClusterGeometryDto previewMerge(@PathVariable UUID id, @Valid @RequestBody MergeRequest body) {
        return ClusterGeometryDto.from(moderation.previewMerge(id, body.sourceIssueId()));
    }

    @PostMapping("/issues/{id}/merge")
    @PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN') and @issueGuard.canAct(#id, authentication)"
            + " and @issueGuard.canAct(#body.sourceIssueId(), authentication)")
    public ModerationResultDto merge(@PathVariable UUID id, @Valid @RequestBody MergeRequest body,
                                     @AuthenticationPrincipal Jwt jwt) {
        Issue target = moderation.merge(id, body.sourceIssueId(), Actors.from(jwt), body.note());
        return new ModerationResultDto(target.getId(), target.getPublicRef(), body.sourceIssueId(), null);
    }

    @PostMapping("/issues/{id}/recategorise")
    @PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN') and @issueGuard.canAct(#id, authentication)")
    public ModerationResultDto recategorise(@PathVariable UUID id, @Valid @RequestBody RecategoriseRequest body,
                                            @AuthenticationPrincipal Jwt jwt) {
        return result(moderation.recategorise(id, body.categoryCode(), Actors.from(jwt), body.note()), null);
    }

    @GetMapping("/issues/{id}/moderation")
    @PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN') and @issueGuard.canAct(#id, authentication)")
    public List<ModerationEntryDto> history(@PathVariable UUID id) {
        return moderation.history(id).stream().map(ModerationEntryDto::from).toList();
    }

    private static ModerationResultDto result(Issue issue, Issue related) {
        return new ModerationResultDto(issue.getId(), issue.getPublicRef(),
                related == null ? null : related.getId(), related == null ? null : related.getPublicRef());
    }
}
