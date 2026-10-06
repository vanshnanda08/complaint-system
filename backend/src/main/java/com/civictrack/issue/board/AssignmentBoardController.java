package com.civictrack.issue.board;

import com.civictrack.issue.QueueTab;
import com.civictrack.user.Actor;
import com.civictrack.user.auth.Actors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The assignment board's reads (blueprint 3.16). Supervisors and
 * administrators only: it carries reporter identities.
 *
 * <p>Scope comes from the token, as on the staff queue; {@code tab} only
 * narrows it. The members list is the one parameterised read, and it is
 * checked: a department supervisor sees their own department's people. A ward
 * officer -- a supervisor with a ward and no department -- may act on any
 * department's issue in their ward (IssueAccessGuard), so they may list any
 * department's members to assign it.
 */
@RestController
@RequestMapping("/api/v1/supervisor")
@RequiredArgsConstructor
public class AssignmentBoardController {

    private static final int MAX_PAGE = 200;

    private final AssignmentBoardService board;

    @GetMapping("/queue")
    @PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN')")
    public List<BoardRowDto> queue(@RequestParam(required = false) String tab,
                                   @RequestParam(defaultValue = "100") int limit,
                                   @RequestParam(defaultValue = "0") int offset,
                                   @AuthenticationPrincipal Jwt jwt) {
        return board.board(Actors.from(jwt), QueueTab.parse(tab),
                Math.min(Math.max(limit, 1), MAX_PAGE), Math.max(offset, 0));
    }

    @GetMapping("/departments/{departmentId}/members")
    @PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN')")
    public List<MemberDto> members(@PathVariable UUID departmentId, @AuthenticationPrincipal Jwt jwt) {
        Actor actor = Actors.from(jwt);
        boolean wardOfficer = actor.departmentId() == null && actor.wardId() != null;
        if (!actor.isAdmin() && !wardOfficer && !departmentId.equals(actor.departmentId())) {
            throw new AccessDeniedException("Not your department");
        }
        return board.members(departmentId);
    }
}
