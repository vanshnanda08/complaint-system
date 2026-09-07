package com.civictrack.user.dto;

import com.civictrack.user.AppUser;
import com.civictrack.user.auth.TokenService;

import java.util.UUID;

/**
 * The login response. Carries the caller's own role and scope so a client can
 * render the right navigation without a second round trip -- and so that
 * nothing in the frontend has to decode a JWT to find out who it is talking
 * for.
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        UUID userId,
        String fullName,
        String role
) {
    public static TokenResponse of(TokenService.TokenPair pair, AppUser user) {
        return new TokenResponse(
                pair.accessToken(), pair.refreshToken(), "Bearer", pair.expiresInSeconds(),
                user.getId(), user.getFullName(), user.getRole().name());
    }
}
