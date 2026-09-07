package com.civictrack.user.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Token settings.
 *
 * <p>Symmetric HS256 rather than an asymmetric key pair. This is a single
 * service that both mints and verifies its own tokens; there is no second
 * party that needs to verify without being able to mint, which is the property
 * RS256 buys and the only reason to pay for key management. If a public
 * verifier ever appears, this is the one class that changes.
 */
@ConfigurationProperties(prefix = "civictrack.security.jwt")
public record JwtProperties(

        /**
         * HMAC key. Must be at least 32 bytes for HS256; the decoder refuses to
         * start otherwise, which is deliberate -- a short key is a silent
         * downgrade of the whole scheme.
         */
        String secret,

        @DefaultValue("civictrack") String issuer,

        /**
         * Short by design. A leaked access token is only useful for this long,
         * and every authorisation claim it carries -- role, department, ward --
         * is at most this stale after a transfer or a demotion.
         */
        @DefaultValue("PT15M") Duration accessTtl,

        /**
         * Long, because the alternative is asking a citizen to log in again
         * every fortnight to report a pothole. Rotation and reuse detection
         * are explicitly cut from this phase, which is recorded in DD-019
         * rather than left as an unstated gap.
         */
        @DefaultValue("P30D") Duration refreshTtl
) {
}
