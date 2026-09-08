package com.civictrack.user.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.time.Clock;
import java.util.List;

/**
 * The decoder and the authority converter, kept out of {@code SecurityConfig}
 * so that the token format is defined in one place alongside the code that
 * mints it.
 */
public final class TokenSupport {

    private TokenSupport() {
    }

    public static NimbusJwtDecoder accessTokenDecoder(JwtProperties props, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(TokenService.secretKey(props))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(
                SecurityJwtValidators.forType(TokenService.TYPE_ACCESS, props.issuer(), clock));
        return decoder;
    }

    /**
     * Maps the single {@code role} claim to one {@code ROLE_} authority.
     *
     * <p>One role per user, not a list. A municipal org chart has exactly one
     * answer to "what is this person"; modelling it as a set would invite
     * combinations -- staff-and-supervisor -- that the transition table has no
     * meaning for, and every guard would then have to decide which of a user's
     * roles applies.
     */
    public static JwtAuthenticationConverter roleClaimConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(TokenSupport::authorities);
        return converter;
    }

    private static List<GrantedAuthority> authorities(Jwt jwt) {
        String role = jwt.getClaimAsString(TokenService.CLAIM_ROLE);
        return role == null ? List.of() : List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
