package com.civictrack.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Citizen self-registration.
 *
 * <p>There is no role field, and that is a design decision rather than an
 * omission: {@link com.civictrack.user.auth.AuthService} always creates a
 * CITIZEN. A body that can name its own role is how demo projects ship
 * privilege escalation.
 */
public record RegisterRequest(

        @Email(message = "email must be a valid address")
        @Size(max = 180)
        String email,

        @Pattern(regexp = "^$|^\\+?[0-9]{7,15}$", message = "phone must be 7-15 digits")
        @Size(max = 20)
        String phone,

        @NotBlank(message = "password is required")
        @Size(min = 10, max = 100, message = "password must be at least 10 characters")
        String password,

        @NotBlank(message = "fullName is required")
        @Size(max = 160)
        String fullName
) {
}
