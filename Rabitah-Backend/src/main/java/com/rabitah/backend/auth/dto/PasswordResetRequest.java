package com.rabitah.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A reset request is public at submission time; only its BCrypt hash is retained. */
public record PasswordResetRequest(
        @NotBlank @Size(max = 32) String loginId,
        @NotBlank @Size(min = 8, max = 72)
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$") String newPassword) {}
