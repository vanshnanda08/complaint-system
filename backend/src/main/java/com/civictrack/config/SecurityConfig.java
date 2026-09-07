package com.civictrack.config;

import com.civictrack.user.auth.JwtProperties;
import com.civictrack.user.auth.TokenSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The security chain.
 *
 * <p>Authentication is Spring Security's OAuth2 resource server (DD-007):
 * {@code JwtDecoder} and {@code JwtAuthenticationConverter}, no hand-written
 * filter and no {@code jjwt}. Hand-rolled authentication filters fail in
 * boring, catastrophic ways -- {@code alg: none}, a missing expiry check, a
 * claim read on a path that returns before verification -- and the framework
 * has already made and fixed every one of them.
 *
 * <p><b>The allow-list is closed by default and stays that way.</b> Everything
 * not named below answers 401 to an anonymous caller. Four groups are open, and
 * each is a decision rather than a convenience:
 *
 * <ul>
 *   <li>The health probes, because a load balancer cannot hold a token.</li>
 *   <li>The public read surface, because "everything here is public, no login"
 *       is the product.</li>
 *   <li>{@code /api/v1/auth/**}, because a caller with no token has to be able
 *       to get one.</li>
 *   <li>{@code POST /api/v1/reports}, because requiring registration before
 *       somebody can report a pothole would collect fewer potholes (DD-017).
 *       Note that permitting it does not mean ignoring a token: a valid bearer
 *       token on this path still authenticates, and the controller attaches the
 *       reporter's identity when one is present.</li>
 * </ul>
 *
 * <p>{@code /actuator/metrics} and {@code /actuator/prometheus} now require
 * ADMIN, which closes DD-015. They publish latency distributions, pool
 * internals and -- through the ingest timer -- report volume and its timing.
 * That is a real information-disclosure surface, and the project's "everything
 * is public" stance is about the city's data, not about the server's internals.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final List<String> allowedOrigins;

    public SecurityConfig(@Value("${civictrack.cors.allowed-origins}") String origins) {
        // Exact origins, never "*". A wildcard is incompatible with
        // credentialed requests and we will be sending an Authorization header.
        this.allowedOrigins = List.of(origins.split("\\s*,\\s*"));
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter converter)
            throws Exception {
        return http
                // Stateless bearer tokens, no cookie-borne session, so there is
                // no CSRF surface for an attacker to ride.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                         "/actuator/info").permitAll()
                        // DD-015, resolved. Exposed and reachable, by an
                        // administrator, rather than exposed and unreachable.
                        .requestMatchers("/actuator/metrics", "/actuator/metrics/**",
                                         "/actuator/prometheus").hasRole("ADMIN")
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/public/**",
                                "/api/v1/dashboard/**",
                                "/api/v1/categories",
                                "/api/v1/wards",
                                "/api/v1/stream/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**",
                                         "/swagger-ui.html").permitAll()
                        // Anonymous reporting is a deliberate product decision
                        // (DD-017): it is the lowest-friction way to report,
                        // and it is what a citizen uses when they do not trust
                        // the system enough to register with it. Device
                        // throttling lands with the photo pipeline; anonymous
                        // reporters cannot vote in verification, which is the
                        // trade-off DD-006 records and measures.
                        .requestMatchers(HttpMethod.POST, "/api/v1/reports").permitAll()
                        .anyRequest().authenticated())
                // Installing the resource server also installs
                // BearerTokenAuthenticationEntryPoint, which is what turns the
                // anonymous rejection from a bare 403 into a 401 carrying
                // WWW-Authenticate: Bearer. Before this phase there was no
                // entry point at all, so an unauthenticated caller was told
                // "forbidden" when the truthful answer was "unauthenticated".
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }

    /**
     * The decoder the resource server uses. It accepts <em>access</em> tokens
     * only: refresh tokens are signed with the same key, so without this check
     * a thirty-day refresh token would be a perfectly valid bearer credential
     * and the fifteen-minute access lifetime would be decorative.
     */
    @Bean
    JwtDecoder jwtDecoder(JwtProperties props, java.time.Clock clock) {
        return TokenSupport.accessTokenDecoder(props, clock);
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        return TokenSupport.roleClaimConverter();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(allowedOrigins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Device-Id"));
        cfg.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // Cost 12. Roughly 250 ms per hash on this hardware, which is the point:
        // it is the difference between an offline attack on a leaked table being
        // expensive and being a weekend.
        return new BCryptPasswordEncoder(12);
    }
}
