package com.civictrack.user;

/**
 * Who is acting.
 *
 * <p>Four of these are user roles and are constrained by the database:
 * {@code users.role CHECK (role IN ('CITIZEN','STAFF','SUPERVISOR','ADMIN'))}.
 * {@link #SYSTEM} is deliberately in the same enum but is <em>never</em>
 * persisted on a user row -- that CHECK constraint is what enforces it. It
 * exists because the transition table names it as an actor: the transitions
 * into RESOLVED and CLOSED are performed by the verification sweep and the
 * auto-close job, not by a person, and modelling that as "some admin account
 * the jobs log in as" would put a human role on a machine action in the audit
 * trail.
 *
 * <p>Spring Security authorities are the role name with a {@code ROLE_} prefix,
 * so {@code hasRole('SUPERVISOR')} in a {@code @PreAuthorize} matches
 * {@code SUPERVISOR} here.
 */
public enum Role {

    CITIZEN,
    STAFF,
    SUPERVISOR,
    ADMIN,

    /** Scheduled jobs and internal engines. Never a login. */
    SYSTEM;

    public String authority() {
        return "ROLE_" + name();
    }

    /** True for the roles that may hold a login. */
    public boolean isHuman() {
        return this != SYSTEM;
    }
}
