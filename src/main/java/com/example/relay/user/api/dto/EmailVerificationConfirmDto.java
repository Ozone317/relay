package com.example.relay.user.api.dto;

import jakarta.validation.constraints.NotBlank;

public record EmailVerificationConfirmDto(@NotBlank String token) {
}
