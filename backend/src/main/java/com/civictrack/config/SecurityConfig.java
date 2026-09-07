package com.civictrack.config;

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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Phase 1 security baseline.
 *
 * <p>Authentication itself lands in phase 3 (per docs/civictrack-claude-code-prompts.md,
 * which is the authoritative build order), and when it does it will be
 * Spring Security's OAuth2 resource server — {@code JwtDecoder} and
 * {@code JwtAuthenticationConverter} — not a hand-written filter. Until then
 * this chain is deliberately closed: only the public read surface and the
 * health probes are open, and everything else answers 401. An empty
 * allow-list is a safer place to start than a permissive one, because
 * endpoints get added to a chain far more often than they get removed.
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
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // Stateless bearer tokens, no cookie-borne session, so there is
                // no CSRF surface for an attacker to ride.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                         "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/public/**",
                                "/api/v1/dashboard/**",
                                "/api/v1/categories",
                                "/api/v1/wards",
                                "/api/v1/stream/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**",
                                         "/swagger-ui.html").permitAll()
                        // Anonymous reporting is a deliberate product decision:
                        // it is the lowest-friction way to report, and it is
                        // what a citizen uses when they do not trust the system
                        // enough to register with it. Device throttling lands
                        // in phase 6; anonymous reporters cannot vote in
                        // verification, which is the trade-off DD-006 records.
                        .requestMatchers(HttpMethod.POST, "/api/v1/reports").permitAll()
                        .anyRequest().authenticated())
                .build();
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
        return new BCryptPasswordEncoder(12);
    }
}
