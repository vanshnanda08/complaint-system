package com.civictrack.auth.dto;

import com.civictrack.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() { }

    /** Blueprint: "email or phone + password" — either identifier is acceptable. */
    public record RegisterRequest(
            @NotBlank @Size(max = 160) String fullName,
            @Email @Size(max = 180) String email,
            @Pattern(regexp = "^$|^[0-9+][0-9 -]{7,19}$", message = "Not a valid phone number")
            String phone,
            @NotBlank @Size(min = 8, max = 72, message = "Password must be at least 8 characters")
            String password) {

        public boolean hasEmail() { return email != null && !email.isBlank(); }
        public boolean hasPhone() { return phone != null && !phone.isBlank(); }
    }

    public record LoginRequest(
            @NotBlank String identifier,   // email or phone
            @NotBlank String password) { }

    public record RefreshRequest(@NotBlank String refreshToken) { }

    public record TokenResponse(
            String accessToken,
            String refreshToken,
            long expiresInSeconds,
            UserSummary user) { }

    public record UserSummary(UUID id, String fullName, String email, String phone, Role role) { }
}
