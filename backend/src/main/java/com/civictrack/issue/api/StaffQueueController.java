package com.civictrack.issue.api;

import com.civictrack.issue.IssueLifecycleService;
import com.civictrack.issue.QueueTab;
import com.civictrack.issue.dto.QueueRowDto;
import com.civictrack.user.auth.Actors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * One department's open work, most urgent first.
 *
 * <p>The scope comes from the caller's own token, never from a query
 * parameter. A {@code ?departmentId=} filter would be an authorisation decision
 * expressed as a convenience, and the first person to notice they can change it
 * gets everybody else's queue.
 *
 * <p>{@code ?tab=} is the exception that proves the rule. It narrows the
 * caller's own scope -- to what is assigned to them, or to what nobody has
 * picked up -- and there is no value of it that reaches an issue the token
 * did not already permit. A view preference may travel in the URL; an
 * authorisation boundary may not.
 */
@RestController
@RequestMapping("/api/v1/staff")
@RequiredArgsConstructor
public class StaffQueueController {

    private static final int MAX_PAGE = 200;

    private final IssueLifecycleService lifecycle;

    @GetMapping("/queue")
    @PreAuthorize("hasAnyRole('STAFF','SUPERVISOR','ADMIN')")
    public List<QueueRowDto> queue(@RequestParam(required = false) String tab,
                                   @RequestParam(defaultValue = "50") int limit,
                                   @RequestParam(defaultValue = "0") int offset,
                                   @AuthenticationPrincipal Jwt jwt) {
        int bounded = Math.min(Math.max(limit, 1), MAX_PAGE);
        return lifecycle.queue(Actors.from(jwt), QueueTab.parse(tab),
                               bounded, Math.max(offset, 0))
                .stream().map(QueueRowDto::from).toList();
    }
}
