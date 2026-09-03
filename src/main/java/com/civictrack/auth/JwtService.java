package com.civictrack.auth;

import com.civictrack.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Stateless JWT issuing/parsing. Access tokens are short (15 min) and carry the
 * role claim; refresh tokens are long (30 d) and carry nothing but the subject,
 * so a stolen refresh token cannot be replayed as an access token.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtService(@Value("${civictrack.jwt.secret}") String secret,
                      @Value("${civictrack.jwt.access-ttl-minutes:15}") long accessMinutes,
                      @Value("${civictrack.jwt.refresh-ttl-days:30}") long refreshDays) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "civictrack.jwt.secret must be at least 256 bits (32 chars); got " + bytes.length);
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessTtl = Duration.ofMinutes(accessMinutes);
        this.refreshTtl = Duration.ofDays(refreshDays);
    }

    public String issueAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("role", user.getRole().name())
                .claim("name", user.getFullName())
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    public String issueRefreshToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("typ", "refresh")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTtl)))
                .signWith(key)
                .compact();
    }

    /** Returns claims only if the signature, expiry and token type all check out. */
    public Claims parse(String token, String expectedType) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (!expectedType.equals(claims.get("typ", String.class))) {
            throw new IllegalArgumentException("Wrong token type");
        }
        return claims;
    }

    public UUID subjectOf(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    public long accessTtlSeconds() { return accessTtl.toSeconds(); }
}
