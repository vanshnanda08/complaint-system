package com.civictrack.auth;

import com.civictrack.auth.dto.AuthDtos.*;
import com.civictrack.common.error.ApiException;
import com.civictrack.user.Role;
import com.civictrack.user.User;
import com.civictrack.user.UserRepository;
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public TokenResponse register(RegisterRequest req) {
        if (!req.hasEmail() && !req.hasPhone()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Provide an email address or a phone number.");
        }
        if (req.hasEmail() && users.existsByEmailIgnoreCase(req.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "That email is already registered.");
        }
        if (req.hasPhone() && users.existsByPhone(req.phone())) {
            throw new ApiException(HttpStatus.CONFLICT, "That phone number is already registered.");
        }

        // Self-registration always yields a CITIZEN. Staff roles are granted by an admin.
        User user = users.save(new User(
                req.hasEmail() ? req.email().toLowerCase() : null,
                req.hasPhone() ? req.phone() : null,
                passwordEncoder.encode(req.password()),
                req.fullName().trim(),
                Role.CITIZEN));

        return tokensFor(user);
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest req) {
        String id = req.identifier().trim();
        Optional<User> found = id.contains("@")
                ? users.findByEmailIgnoreCase(id)
                : users.findByPhone(id);

        // Uniform failure: never reveal whether the account exists.
        User user = found.orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "Incorrect email/phone or password."));

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Incorrect email/phone or password.");
        }
        if (!user.isActive()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This account has been deactivated.");
        }
        return tokensFor(user);
    }

    @Transactional(readOnly = true)
    public TokenResponse refresh(RefreshRequest req) {
        try {
            Claims claims = jwtService.parse(req.refreshToken(), "refresh");
            User user = users.findById(jwtService.subjectOf(claims))
                    .filter(User::isActive)
                    .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Unknown account."));
            return tokensFor(user);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Session expired. Please sign in again.");
        }
    }

    private TokenResponse tokensFor(User user) {
        return new TokenResponse(
                jwtService.issueAccessToken(user),
                jwtService.issueRefreshToken(user),
                jwtService.accessTtlSeconds(),
                summary(user));
    }

    public UserSummary summary(User user) {
        return new UserSummary(user.getId(), user.getFullName(),
                user.getEmail(), user.getPhone(), user.getRole());
    }
}
