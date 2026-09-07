package com.civictrack.user;

import java.util.UUID;

/**
 * Whoever is attempting an action, reduced to what an authorisation decision
 * needs: identity, role, and the two scopes.
 *
 * <p>This is not the entity. Every authorisation check in the request path
 * runs against claims already carried in the validated JWT, so a staff member
 * acknowledging an issue costs zero extra queries. The entity is loaded only
 * where the action needs more than identity -- assigning work to someone, for
 * instance, which has to confirm the assignee exists and is in the right
 * department.
 */
public record Actor(UUID id, Role role, UUID departmentId, UUID wardId) {

    /** The actor for scheduled jobs and internal engines. Has no identity. */
    public static Actor system() {
        return new Actor(null, Role.SYSTEM, null, null);
    }

    public boolean isSystem() {
        return role == Role.SYSTEM;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
