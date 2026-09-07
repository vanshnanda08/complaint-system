package com.civictrack.user.api;

import com.civictrack.user.AppUser;
import com.civictrack.user.auth.AuthService;
import com.civictrack.user.dto.LoginRequest;
import com.civictrack.user.dto.RefreshRequest;
import com.civictrack.user.dto.RegisterRequest;
import com.civictrack.user.dto.TokenResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registration, login and refresh.
 *
 * <p>These three paths are the only ones in the application that are permitted
 * anonymously <em>and</em> hand out credentials, so they are also the paths a
 * rate limiter belongs on. Device and endpoint throttling is a later phase; the
 * gap is named here rather than left to be discovered.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService auth;

    @PostMapping("/register")
    public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request) {
        AppUser user = auth.register(request.email(), emptyToNull(request.phone()),
                request.password(), request.fullName());
        AuthService.Authenticated result = auth.login(user.getEmail(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TokenResponse.of(result.tokens(), result.user()));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        AuthService.Authenticated result = auth.login(request.email(), request.password());
        return TokenResponse.of(result.tokens(), result.user());
    }

    /**
     * Exchanges a refresh token for a fresh pair.
     *
     * <p>The old refresh token stays valid: rotation and reuse detection are
     * explicitly out of scope for this phase (DD-019). What is <em>not</em>
     * skipped is the type check -- a refresh token is rejected as a bearer
     * credential everywhere else, which is what stops the thirty-day token
     * quietly becoming a thirty-day access token.
     */
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        AuthService.Authenticated result = auth.refresh(request.refreshToken());
        return TokenResponse.of(result.tokens(), result.user());
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
