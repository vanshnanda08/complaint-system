package com.civictrack.user.auth;

import com.civictrack.user.AppUser;
import com.civictrack.user.Role;
import com.civictrack.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Registration, login, and refresh.
 *
 * <p>Registration only ever creates {@link Role#CITIZEN}. Staff, supervisor and
 * administrator accounts are created by an administrator or seeded by
 * migration; a self-service endpoint that accepts a role from the request body
 * is the single most common way a demo project ships a privilege-escalation
 * bug, and the role is therefore not a parameter here at all rather than being
 * a parameter that is validated.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;

    @Transactional
    public AppUser register(String email, String phone, String rawPassword, String fullName) {
        String normalised = email == null ? null : email.trim().toLowerCase();
        if (normalised != null && users.existsByEmailIgnoreCase(normalised)) {
            throw new EmailAlreadyRegisteredException(normalised);
        }
        if (phone != null && users.existsByPhone(phone)) {
            throw new EmailAlreadyRegisteredException(phone);
        }

        AppUser user = new AppUser();
        user.setEmail(normalised);
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setFullName(fullName);
        user.setRole(Role.CITIZEN);
        return users.save(user);
    }

    @Transactional(readOnly = true)
    public Authenticated login(String email, String rawPassword) {
        AppUser user = users.findByEmailIgnoreCase(email == null ? "" : email.trim())
                .orElseThrow(() -> new InvalidCredentialsException("Email or password is incorrect"));
        verify(user, rawPassword);
        return new Authenticated(user, tokens.issue(user));
    }

    @Transactional(readOnly = true)
    public Authenticated refresh(String refreshToken) {
        UUID subject = tokens.parseRefresh(refreshToken);
        AppUser user = users.findById(subject)
                .orElseThrow(() -> new InvalidCredentialsException("The account no longer exists"));
        if (!user.isActive()) {
            throw new InvalidCredentialsException("The account is disabled");
        }
        // Deliberately re-read rather than trusting the claims in the refresh
        // token: over thirty days a user can change department, be demoted, or
        // be deactivated, and a refreshed access token must reflect the account
        // as it is now rather than as it was when the refresh token was minted.
        return new Authenticated(user, tokens.issue(user));
    }

    private void verify(AppUser user, String rawPassword) {
        if (!user.isActive()) {
            throw new InvalidCredentialsException("Email or password is incorrect");
        }
        if (user.getPasswordHash() == null) {
            // The V4 org-chart accounts land here. A null hash must never be
            // handed to the encoder: matches(raw, null) is an implementation
            // detail of the encoder, and depending on it would make "can this
            // seeded account be logged into?" a question about BCrypt rather
            // than about our own policy.
            throw new InvalidCredentialsException("Email or password is incorrect");
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException("Email or password is incorrect");
        }
    }

    /** The account and the freshly minted pair, so callers need neither a second lookup nor a token parse. */
    public record Authenticated(AppUser user, TokenService.TokenPair tokens) {
    }
}
