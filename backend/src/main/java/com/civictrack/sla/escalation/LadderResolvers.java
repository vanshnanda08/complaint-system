package com.civictrack.sla.escalation;

import com.civictrack.issue.Issue;
import com.civictrack.user.AppUser;
import com.civictrack.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The four rungs, one class each.
 *
 * <p>They are grouped in one file because the ladder is one idea and reading it
 * as four scattered classes would hide the shape of it. Each is still a
 * separate bean, discovered by {@link EscalationLadder} and ordered by its own
 * declared level rather than by declaration order in this file.
 */
public final class LadderResolvers {

    private LadderResolvers() {
    }

    /** Level 1: the head of the department that owns the issue. */
    @Component
    @RequiredArgsConstructor
    public static class DepartmentHead implements EscalationResolver {

        private final UserRepository users;

        @Override
        public int level() {
            return 1;
        }

        @Override
        public Optional<UUID> resolve(Issue issue) {
            return issue.getDepartmentId() == null
                    ? Optional.empty()
                    : users.findDepartmentHead(issue.getDepartmentId());
        }

        @Override
        public String describe() {
            return "department head";
        }
    }

    /**
     * Level 2: the head of the parent department. One hop up
     * {@code parent_department_id}, and only one -- this is the single step
     * where the specification's tree walk was actually correct.
     */
    @Component
    @RequiredArgsConstructor
    public static class ParentDepartmentHead implements EscalationResolver {

        private final UserRepository users;

        @Override
        public int level() {
            return 2;
        }

        @Override
        public Optional<UUID> resolve(Issue issue) {
            return issue.getDepartmentId() == null
                    ? Optional.empty()
                    : users.findParentDepartmentHead(issue.getDepartmentId());
        }

        @Override
        public String describe() {
            return "parent department head";
        }
    }

    /**
     * Level 3: the ward officer.
     *
     * <p>This is the rung that broke the tree walk, and it is also the rung
     * that makes the ladder mean something: at level 3 the issue stops being a
     * departmental failure and becomes a geographic one. The owner changes from
     * somebody accountable for a service to somebody accountable for a place,
     * which is exactly the escalation a resident would recognise.
     */
    @Component
    @RequiredArgsConstructor
    public static class WardOfficer implements EscalationResolver {

        private final UserRepository users;

        @Override
        public int level() {
            return 3;
        }

        @Override
        public Optional<UUID> resolve(Issue issue) {
            return users.findWardOfficer(issue.getWardId());
        }

        @Override
        public String describe() {
            return "ward officer";
        }
    }

    /** Level 4: the commissioner. Terminal -- there is no level 5. */
    @Component
    @RequiredArgsConstructor
    public static class Administrator implements EscalationResolver {

        private final UserRepository users;

        @Override
        public int level() {
            return 4;
        }

        @Override
        public Optional<UUID> resolve(Issue issue) {
            List<AppUser> admins = users.findAdmins();
            return admins.isEmpty() ? Optional.empty() : Optional.of(admins.get(0).getId());
        }

        @Override
        public String describe() {
            return "administrator (terminal)";
        }
    }
}
