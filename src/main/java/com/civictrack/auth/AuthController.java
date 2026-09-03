package com.civictrack.auth;

import com.civictrack.auth.dto.AuthDtos.*;
import com.civictrack.common.error.ApiException;
import com.civictrack.user.UserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final UserRepository users;

    public AuthController(AuthService authService, UserRepository users) {
        this.authService = authService;
        this.users = users;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest req) {
        return authService.register(req);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return authService.refresh(req);
    }

    /** Protected: proves the JWT round-trip works end to end. */
    @GetMapping("/me")
    public UserSummary me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return users.findById(principal.id())
                .map(authService::summary)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Unknown account."));
    }
}
