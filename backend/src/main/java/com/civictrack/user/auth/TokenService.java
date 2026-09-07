package com.civictrack.user.auth;

import com.civictrack.user.AppUser;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Mints and verifies the two token types.
 *
 * <p>Both are JWTs signed with the same key, which creates one hazard worth
 * naming: without a distinguishing claim, a thirty-day refresh token would be
 * a perfectly valid bearer credential for thirty days -- silently converting
 * the short access-token lifetime into a thirty-day one and defeating the whole
 * point of having two. Every token therefore carries {@code typ}, the resource
 * server's decoder rejects anything that is not {@code access}, and
 * {@link #parseRefresh} rejects anything that is not {@code refresh}. The two
 * checks are in opposite directions on purpose: neither token can stand in for
 * the other. {@code TokenTypeSeparationIT} is the guard on that.
 */
@Service
public class TokenService {

    public static final String CLAIM_TYPE = "typ";
    public static final String CLAIM_ROLE = "role";
    static final String CLAIM_DEPARTMENT = "dept";
    static final String CLAIM_WARD = "ward";
    static final String CLAIM_NAME = "name";
    public static final String TYPE_ACCESS = "access";
    static final String TYPE_REFRESH = "refresh";

    private final JwtProperties props;
    private final Clock clock;
    private final JwtEncoder encoder;
    private final NimbusJwtDecoder refreshDecoder;

    public TokenService(JwtProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        SecretKeySpec key = secretKey(props);
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.refreshDecoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256).build();
        this.refreshDecoder.setJwtValidator(
                SecurityJwtValidators.forType(TYPE_REFRESH, props.issuer(), clock));
    }

    public static SecretKeySpec secretKey(JwtProperties props) {
        byte[] bytes = props.secret() == null
                ? new byte[0] : props.secret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            // Fail at startup rather than at the first login. An undersized
            // HMAC key is a downgrade of the signature strength that nothing
            // else in the system would report.
            throw new IllegalStateException(
                    "civictrack.security.jwt.secret must be at least 32 bytes for HS256; got "
                    + bytes.length + ". Set it via the JWT_SECRET environment variable.");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    public TokenPair issue(AppUser user) {
        Instant now = clock.instant();
        return new TokenPair(
                sign(user, TYPE_ACCESS, now, props.accessTtl()),
                sign(user, TYPE_REFRESH, now, props.refreshTtl()),
                props.accessTtl().toSeconds());
    }

    /** Verifies a refresh token and returns the subject it names. */
    public UUID parseRefresh(String token) {
        try {
            Jwt jwt = refreshDecoder.decode(token);
            return UUID.fromString(jwt.getSubject());
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidCredentialsException("The refresh token is invalid or has expired");
        }
    }

    private String sign(AppUser user, String type, Instant now, java.time.Duration ttl) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .subject(user.getId().toString())
                .claim(CLAIM_TYPE, type)
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_NAME, user.getFullName());

        // Authorisation scope travels in the token so that the guard bean can
        // answer "same department?" without a query on every request. The cost
        // is staleness bounded by the access-token TTL, which is what makes
        // fifteen minutes the right number rather than an arbitrary one.
        if (user.getDepartmentId() != null) {
            claims.claim(CLAIM_DEPARTMENT, user.getDepartmentId().toString());
        }
        if (user.getWardId() != null) {
            claims.claim(CLAIM_WARD, user.getWardId().toString());
        }
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }

    public record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {
    }
}
