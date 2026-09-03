package com.civictrack.user;

/**
 * Blueprint §6.1 permission matrix. Only CITIZEN is self-registerable;
 * staff accounts are provisioned by an ADMIN.
 */
public enum Role {
    CITIZEN, STAFF, SUPERVISOR, ADMIN
}
