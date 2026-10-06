package com.civictrack.verification.api;

import com.civictrack.user.Actor;
import com.civictrack.user.auth.Actors;
import com.civictrack.verification.VerificationService;
import com.civictrack.verification.VerificationViewService;
import com.civictrack.verification.dto.AwaitingVerdictDto;
import com.civictrack.verification.dto.VerificationViewDto;
import com.civictrack.verification.dto.VerifyRequest;
import com.civictrack.verification.dto.VoteResultDto;
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

import java.util.List;
import java.util.UUID;

/**
 * Citizen verification (section 4.3, blueprint 3.12).
 *
 * <p>The vote endpoint admits CITIZEN only, and only one of this issue's
 * reporters. The role restriction is not in section 4.3 and was added here
 * (DD-061): a crew member who happened to report a pothole on their way to
 * work would otherwise be able to confirm their own department's fix, which
 * is the self-certification the transition table exists to rule out.
 *
 * <p>Anonymous reporters cannot vote at all -- there is no token to vote with.
 * That is DD-006, measured on the dashboard rather than closed.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class VerificationController {

    private final VerificationService verification;
    private final VerificationViewService views;

    @PostMapping("/issues/{id}/verify")
    @PreAuthorize("hasRole('CITIZEN') and @issueGuard.canVerify(#id, authentication)")
    public VoteResultDto verify(@PathVariable UUID id,
                                @Valid @RequestBody VerifyRequest body,
                                @AuthenticationPrincipal Jwt jwt) {
        return VoteResultDto.from(verification.vote(id, actor(jwt), body.verdict(), body.reason()));
    }

    /** Fixes waiting on the caller's answer. */
    @GetMapping("/me/verifications")
    @PreAuthorize("isAuthenticated()")
    public List<AwaitingVerdictDto> awaiting(@AuthenticationPrincipal Jwt jwt) {
        return views.awaiting(actor(jwt));
    }

    @GetMapping("/me/verifications/{id}")
    @PreAuthorize("isAuthenticated()")
    public VerificationViewDto view(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return views.view(id, actor(jwt));
    }

    private static Actor actor(Jwt jwt) {
        return Actors.from(jwt);
    }
}
