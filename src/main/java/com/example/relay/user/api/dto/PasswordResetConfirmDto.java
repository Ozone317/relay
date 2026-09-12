package com.example.relay.user.api.dto;

import com.example.relay.common.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;

public record PasswordResetConfirmDto(@NotBlank String token, @NotBlank @ValidPassword String newPassword) {
}
