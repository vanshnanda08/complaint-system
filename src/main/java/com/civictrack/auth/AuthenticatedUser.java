package com.civictrack.auth;

import com.civictrack.user.Role;
import com.civictrack.user.User;
import java.util.UUID;

/** The principal placed in the SecurityContext. Blueprint §6.2's guard beans read this. */
public record AuthenticatedUser(UUID id, String fullName, String email, Role role) {

    public AuthenticatedUser(User user) {
        this(user.getId(), user.getFullName(), user.getEmail(), user.getRole());
    }
}
