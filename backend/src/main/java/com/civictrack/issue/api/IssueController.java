package com.civictrack.issue.api;

import com.civictrack.issue.IssueLifecycleService;
import com.civictrack.issue.dto.AssignRequest;
import com.civictrack.issue.dto.IssueDto;
import com.civictrack.issue.dto.RejectRequest;
import com.civictrack.issue.dto.ResolveRequest;
import com.civictrack.issue.dto.TransitionRequest;
import com.civictrack.user.Actor;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The lifecycle endpoints, one per verb in the transition table.
 *
 * <p>Both authorisation layers are visible on every method. {@code hasAnyRole}
 * answers "can this kind of user do this kind of thing"; {@code @issueGuard} --
 * the bean in {@link com.civictrack.issue.IssueAccessGuard} -- answers "is this
 * specific user allowed to touch this specific issue". Either alone is
 * insufficient: the first lets Sanitation act on a Roads ticket, and the second
 * would let a citizen who reported an issue acknowledge it.
 *
 * <p>There is no {@code /resolve} endpoint that reaches RESOLVED, and no
 * {@code /close} for staff. The absence is the feature.
 */
@RestController
@RequestMapping("/api/v1/issues")
@RequiredArgsConstructor
public class IssueController {

    private final IssueLifecycleService lifecycle;

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('STAFF','SUPERVISOR','ADMIN') and @issueGuard.canAct(#id, authentication)")
    public IssueDto get(@PathVariable UUID id) {
        return IssueDto.from(lifecycle.get(id));
    }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasAnyRole('STAFF','SUPERVISOR','ADMIN') and @issueGuard.canAct(#id, authentication)")
    public IssueDto acknowledge(@PathVariable UUID id,
                                @Valid @RequestBody(required = false) TransitionRequest body,
                                @AuthenticationPrincipal Jwt jwt) {
        return IssueDto.from(lifecycle.acknowledge(id, actor(jwt), noteOf(body)));
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN') and @issueGuard.canAct(#id, authentication)")
    public IssueDto assign(@PathVariable UUID id,
                           @Valid @RequestBody AssignRequest body,
                           @AuthenticationPrincipal Jwt jwt) {
        return IssueDto.from(lifecycle.assign(id, actor(jwt), body.assigneeId(), body.note()));
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasAnyRole('STAFF','SUPERVISOR','ADMIN') and @issueGuard.canAct(#id, authentication)")
    public IssueDto start(@PathVariable UUID id,
                          @Valid @RequestBody(required = false) TransitionRequest body,
                          @AuthenticationPrincipal Jwt jwt) {
        return IssueDto.from(lifecycle.start(id, actor(jwt), noteOf(body)));
    }

    /**
     * Submits a fix for citizen verification. Named for what it does rather
     * than for what staff would like it to do: it does not resolve anything.
     */
    @PostMapping("/{id}/submit-for-verification")
    @PreAuthorize("hasAnyRole('STAFF','SUPERVISOR','ADMIN') and @issueGuard.canAct(#id, authentication)")
    public IssueDto submitForVerification(@PathVariable UUID id,
                                          @Valid @RequestBody ResolveRequest body,
                                          @AuthenticationPrincipal Jwt jwt) {
        return IssueDto.from(lifecycle.submitForVerification(
                id, actor(jwt), body.proofPhotoUrl(), body.note()));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN') and @issueGuard.canAct(#id, authentication)")
    public IssueDto reject(@PathVariable UUID id,
                           @Valid @RequestBody RejectRequest body,
                           @AuthenticationPrincipal Jwt jwt) {
        return IssueDto.from(lifecycle.reject(id, actor(jwt), body.reason()));
    }

    /** Force close. ADMIN only, and still subject to the seven-day guard. */
    @PostMapping("/{id}/close")
    // SUPERVISOR as well as ADMIN, matching the transition table. The endpoint
    // guard and the policy have to agree: a @PreAuthorize that is stricter
    // than the table produces a 403 where the policy would have allowed it,
    // and the client -- which mirrors the table -- would offer a button that
    // always fails. That exact mismatch cost a phase once (DD-035).
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public IssueDto close(@PathVariable UUID id,
                          @Valid @RequestBody(required = false) TransitionRequest body,
                          @AuthenticationPrincipal Jwt jwt) {
        return IssueDto.from(lifecycle.close(id, actor(jwt), noteOf(body)));
    }

    private static Actor actor(Jwt jwt) {
        return Actors.from(jwt);
    }

    private static String noteOf(TransitionRequest body) {
        return body == null ? null : body.note();
    }
}
