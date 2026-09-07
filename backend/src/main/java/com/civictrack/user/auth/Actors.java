package com.civictrack.user.auth;

import com.civictrack.user.Actor;
import com.civictrack.user.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * Turns a validated token into an {@link Actor}.
 *
 * <p>The claims are trusted here because the token's signature and its
 * validators have already been checked by the resource server before any
 * controller method runs. That is the whole argument for using the framework's
 * decoder rather than a hand-written filter (DD-007): the ordering of "verify,
 * then read claims" is the framework's responsibility, and it is exactly the
 * ordering that hand-rolled filters get wrong.
 */
public final class Actors {

    private Actors() {
    }

    /** The actor for a request, or {@code null} when the caller is anonymous. */
    public static Actor from(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getPrincipal() instanceof Jwt jwt ? from(jwt) : null;
    }

    public static Actor from(Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        return new Actor(
                UUID.fromString(jwt.getSubject()),
                Role.valueOf(jwt.getClaimAsString(TokenService.CLAIM_ROLE)),
                uuid(jwt.getClaimAsString(TokenService.CLAIM_DEPARTMENT)),
                uuid(jwt.getClaimAsString(TokenService.CLAIM_WARD)));
    }

    private static UUID uuid(String raw) {
        return raw == null ? null : UUID.fromString(raw);
    }
}
