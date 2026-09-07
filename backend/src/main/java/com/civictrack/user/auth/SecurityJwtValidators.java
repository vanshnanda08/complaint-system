package com.civictrack.user.auth;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Validators applied to every decoded token, on top of signature and expiry.
 *
 * <p>The type check is the important one and is explained in
 * {@link TokenService}: access and refresh tokens are signed with the same key,
 * so only a claim distinguishes them, and only a validator makes that claim
 * mean anything.
 */
public final class SecurityJwtValidators {

    private SecurityJwtValidators() {
    }

    /**
     * Expiry is checked against the application's own {@link Clock}, not
     * against {@code Clock.systemUTC()}.
     *
     * <p>Spring's default timestamp validator uses the system clock, which is
     * right in production and wrong under test: a suite that moves time to
     * exercise a three-day-old issue mints tokens whose {@code exp} the
     * validator then judges against a completely different notion of now, and
     * every authenticated request comes back 401 for a reason that has nothing
     * to do with what the test is about. One clock for the whole application is
     * the simpler rule, and this is the only place it needed enforcing.
     */
    public static OAuth2TokenValidator<Jwt> forType(String expectedType, String issuer, Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(60));
        timestamps.setClock(clock);
        return new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(List.of(
                timestamps,
                issuerIs(issuer),
                typeIs(expectedType)));
    }

    private static OAuth2TokenValidator<Jwt> typeIs(String expected) {
        return jwt -> expected.equals(jwt.getClaimAsString(TokenService.CLAIM_TYPE))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                        "Expected a '" + expected + "' token", null));
    }

    private static OAuth2TokenValidator<Jwt> issuerIs(String expected) {
        return jwt -> expected.equals(jwt.getClaimAsString(JwtClaimNames.ISS))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                        "Unexpected issuer", null));
    }
}
