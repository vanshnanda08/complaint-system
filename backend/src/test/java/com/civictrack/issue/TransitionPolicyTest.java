package com.civictrack.issue;

import com.civictrack.issue.policy.ForbiddenTransitionException;
import com.civictrack.issue.policy.Guards;
import com.civictrack.issue.policy.IllegalTransitionException;
import com.civictrack.issue.policy.TransitionContext;
import com.civictrack.issue.policy.TransitionGuardException;
import com.civictrack.issue.policy.TransitionPolicy;
import com.civictrack.issue.policy.VerificationTally;
import com.civictrack.user.Actor;
import com.civictrack.user.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The transition table, tested as a table.
 *
 * <p>This is a plain unit test with no Spring context and no database, which is
 * the point of {@link TransitionContext}: guards are pure functions of
 * {@code (issue, actor, context)}, so all eighty-one state pairs across every
 * role can be enumerated in milliseconds. A policy that needed a database to
 * answer "may a citizen close an issue?" would be tested on a handful of cases
 * and trusted for the rest.
 *
 * <p>It lives in {@code com.civictrack.issue} rather than beside the policy
 * because {@code Issue.setStatus} is package-private -- standing rule 5 -- and
 * a test that puts an issue into a given state has to be somewhere that is
 * allowed to.
 */
class TransitionPolicyTest {

    private static final Instant NOW = Instant.parse("2026-03-01T09:00:00Z");
    private static final UUID DEPARTMENT = UUID.randomUUID();
    private static final UUID WARD = UUID.randomUUID();
    private static final UUID ASSIGNEE = UUID.randomUUID();

    private final TransitionPolicy policy = new TransitionPolicy();

    /**
     * The table, restated independently of the implementation.
     *
     * <p>Deliberately a second copy. A test that read the edges out of the
     * policy and then asserted the policy allowed them would pass for any
     * table whatsoever, including an empty one.
     */
    private static final List<Edge> EXPECTED = List.of(
            new Edge(IssueStatus.NEW, IssueStatus.ACKNOWLEDGED,
                    Set.of(Role.STAFF, Role.SUPERVISOR, Role.ADMIN)),
            new Edge(IssueStatus.ACKNOWLEDGED, IssueStatus.ASSIGNED,
                    Set.of(Role.SUPERVISOR, Role.ADMIN)),
            new Edge(IssueStatus.ASSIGNED, IssueStatus.IN_PROGRESS,
                    Set.of(Role.STAFF, Role.SUPERVISOR, Role.ADMIN)),
            new Edge(IssueStatus.REOPENED, IssueStatus.IN_PROGRESS,
                    Set.of(Role.STAFF, Role.SUPERVISOR, Role.ADMIN)),
            new Edge(IssueStatus.IN_PROGRESS, IssueStatus.PENDING_VERIFICATION,
                    Set.of(Role.STAFF, Role.SUPERVISOR, Role.ADMIN)),
            new Edge(IssueStatus.PENDING_VERIFICATION, IssueStatus.RESOLVED, Set.of(Role.SYSTEM)),
            new Edge(IssueStatus.PENDING_VERIFICATION, IssueStatus.REOPENED, Set.of(Role.SYSTEM)),
            new Edge(IssueStatus.RESOLVED, IssueStatus.REOPENED, Set.of(Role.SYSTEM)),
            new Edge(IssueStatus.RESOLVED, IssueStatus.CLOSED, Set.of(Role.SYSTEM, Role.ADMIN)),
            new Edge(IssueStatus.NEW, IssueStatus.REJECTED, Set.of(Role.SUPERVISOR, Role.ADMIN)),
            new Edge(IssueStatus.ACKNOWLEDGED, IssueStatus.REJECTED, Set.of(Role.SUPERVISOR, Role.ADMIN)),
            new Edge(IssueStatus.ASSIGNED, IssueStatus.REJECTED, Set.of(Role.SUPERVISOR, Role.ADMIN)),
            new Edge(IssueStatus.IN_PROGRESS, IssueStatus.REJECTED, Set.of(Role.SUPERVISOR, Role.ADMIN)),
            new Edge(IssueStatus.PENDING_VERIFICATION, IssueStatus.REJECTED,
                    Set.of(Role.SUPERVISOR, Role.ADMIN)),
            new Edge(IssueStatus.REOPENED, IssueStatus.REJECTED, Set.of(Role.SUPERVISOR, Role.ADMIN)));

    // ------------------------------------------------------------------
    // the structural rule
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("no staff member reaches RESOLVED or CLOSED, from anywhere")
    class StructuralRule {

        /**
         * The claim the whole project makes about accountability, tested by
         * exhaustion rather than by inspection: from every one of the nine
         * states, a STAFF actor with every guard satisfied still cannot resolve.
         */
        @ParameterizedTest(name = "STAFF cannot resolve from {0}")
        @EnumSource(IssueStatus.class)
        void staffCannotReachResolvedFromAnyState(IssueStatus from) {
            assertThatThrownBy(() -> policy.check(
                    issueAt(from), IssueStatus.RESOLVED, staff(), fullyPermissiveContext()))
                    .as("staff reached RESOLVED from %s -- staff close their own tickets", from)
                    .isInstanceOfAny(IllegalTransitionException.class,
                            ForbiddenTransitionException.class);
        }

        @ParameterizedTest(name = "STAFF cannot close from {0}")
        @EnumSource(IssueStatus.class)
        void staffCannotReachClosedFromAnyState(IssueStatus from) {
            assertThatThrownBy(() -> policy.check(
                    issueAt(from), IssueStatus.CLOSED, staff(), fullyPermissiveContext()))
                    .isInstanceOfAny(IllegalTransitionException.class,
                            ForbiddenTransitionException.class);
        }

        @Test
        @DisplayName("and no rule anywhere in the table names STAFF on those targets")
        void tableItselfNeverNamesStaffOnTerminalSuccess() {
            for (IssueStatus from : IssueStatus.values()) {
                for (IssueStatus to : TransitionPolicy.STAFF_MAY_NEVER_REACH) {
                    assertThat(policy.actorsFor(from, to))
                            .as("%s -> %s", from, to)
                            .doesNotContain(Role.STAFF);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // the table as a whole
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every transition not in the table is refused with IllegalTransitionException")
    void everyUndefinedTransitionIsRefused() {
        List<String> wronglyAllowed = new ArrayList<>();

        for (IssueStatus from : IssueStatus.values()) {
            for (IssueStatus to : IssueStatus.values()) {
                boolean expected = EXPECTED.stream()
                        .anyMatch(e -> e.from() == from && e.to() == to);
                if (expected || policy.isDefined(from, to) == expected) {
                    continue;
                }
                wronglyAllowed.add(from + " -> " + to);
            }
        }

        assertThat(wronglyAllowed)
                .as("these edges exist in the policy but not in the specified table")
                .isEmpty();

        // ... and every edge that should exist, does.
        for (Edge edge : EXPECTED) {
            assertThat(policy.isDefined(edge.from(), edge.to()))
                    .as("%s -> %s is missing from the policy", edge.from(), edge.to())
                    .isTrue();
            assertThat(policy.actorsFor(edge.from(), edge.to()))
                    .as("%s -> %s has the wrong actor set", edge.from(), edge.to())
                    .isEqualTo(edge.actors());
        }
    }

    @Test
    @DisplayName("a role not on an edge is refused with ForbiddenTransitionException, not Illegal")
    void wrongRoleIsDistinguishedFromWrongEdge() {
        assertThatThrownBy(() -> policy.check(issueAt(IssueStatus.NEW), IssueStatus.REJECTED,
                staff(), fullyPermissiveContext()))
                .as("STAFF may not reject; the edge itself exists for SUPERVISOR")
                .isInstanceOf(ForbiddenTransitionException.class);

        assertThatThrownBy(() -> policy.check(issueAt(IssueStatus.NEW), IssueStatus.IN_PROGRESS,
                staff(), fullyPermissiveContext()))
                .as("no NEW -> IN_PROGRESS edge exists for anybody")
                .isInstanceOf(IllegalTransitionException.class);
    }

    // ------------------------------------------------------------------
    // individual guards
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a fix cannot be claimed without a proof photo")
    void proofPhotoIsRequired() {
        TransitionContext noPhoto = TransitionContext.at(NOW)
                .note("Replaced the cover and reset the surrounding tarmac")
                .build();

        assertThatThrownBy(() -> policy.check(issueAt(IssueStatus.IN_PROGRESS),
                IssueStatus.PENDING_VERIFICATION, staff(), noPhoto))
                .isInstanceOf(TransitionGuardException.class)
                .extracting(e -> ((TransitionGuardException) e).getGuard())
                .isEqualTo(Guards.PROOF_PHOTO_PRESENT.key());
    }

    @Test
    @DisplayName("a fix cannot be claimed with a one-word note")
    void noteMustBeSubstantial() {
        TransitionContext terse = TransitionContext.at(NOW)
                .proofPhotoUrl("https://example.test/after.jpg")
                .note("done")
                .build();

        assertThatThrownBy(() -> policy.check(issueAt(IssueStatus.IN_PROGRESS),
                IssueStatus.PENDING_VERIFICATION, staff(), terse))
                .isInstanceOf(TransitionGuardException.class)
                .extracting(e -> ((TransitionGuardException) e).getGuard())
                .isEqualTo(Guards.NOTE_MIN_20.key());
    }

    @Test
    @DisplayName("staff in another department cannot acknowledge")
    void departmentScopeIsEnforcedByTheTableToo() {
        Actor otherDepartment = new Actor(UUID.randomUUID(), Role.STAFF, UUID.randomUUID(), null);

        assertThatThrownBy(() -> policy.check(issueAt(IssueStatus.NEW), IssueStatus.ACKNOWLEDGED,
                otherDepartment, fullyPermissiveContext()))
                .isInstanceOf(TransitionGuardException.class)
                .extracting(e -> ((TransitionGuardException) e).getGuard())
                .isEqualTo(Guards.SAME_DEPARTMENT.key());
    }

    @Test
    @DisplayName("a ward officer can act on any issue in their ward")
    void wardOfficerIsInScopeWithoutADepartment() {
        Actor officer = new Actor(UUID.randomUUID(), Role.SUPERVISOR, null, WARD);

        assertThatCode(() -> policy.check(issueAt(IssueStatus.NEW), IssueStatus.ACKNOWLEDGED,
                officer, fullyPermissiveContext()))
                .as("level 3 of the ladder hands issues to this person; they must be able to act")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an issue cannot be closed before it has been resolved for seven days")
    void closeRequiresTheSettlingPeriod() {
        Issue issue = issueAt(IssueStatus.RESOLVED);
        issue.setResolvedAt(NOW.minus(Duration.ofDays(3)));

        assertThatThrownBy(() -> policy.check(issue, IssueStatus.CLOSED, admin(),
                fullyPermissiveContext()))
                .isInstanceOf(TransitionGuardException.class)
                .extracting(e -> ((TransitionGuardException) e).getGuard())
                .isEqualTo(Guards.SETTLED_7_DAYS.key());

        issue.setResolvedAt(NOW.minus(Duration.ofDays(8)));
        assertThatCode(() -> policy.check(issue, IssueStatus.CLOSED, admin(),
                fullyPermissiveContext())).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Issue issueAt(IssueStatus status) {
        Issue issue = new Issue();
        issue.setId(UUID.randomUUID());
        issue.setDepartmentId(DEPARTMENT);
        issue.setWardId(WARD);
        issue.setAssignedTo(ASSIGNEE);
        issue.setStatus(status);
        return issue;
    }

    /**
     * Every guard satisfied. Used by the structural tests so that a refusal can
     * only be the role or the missing edge -- never an incidental precondition
     * that would make the test pass for the wrong reason.
     */
    private static TransitionContext fullyPermissiveContext() {
        return TransitionContext.at(NOW)
                .note("A note that is comfortably longer than twenty characters")
                .proofPhotoUrl("https://example.test/after.jpg")
                .assignee(ASSIGNEE, DEPARTMENT)
                .recurrenceWithinWindow(true)
                .tally(new VerificationTally(3, 3, 0, true))
                .build();
    }

    private static Actor staff() {
        return new Actor(ASSIGNEE, Role.STAFF, DEPARTMENT, null);
    }

    private static Actor admin() {
        return new Actor(UUID.randomUUID(), Role.ADMIN, null, null);
    }

    private record Edge(IssueStatus from, IssueStatus to, Set<Role> actors) {
    }
}
