package com.example.relay.user.api.dto;

import com.example.relay.common.validation.ValidPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record RegisterRequest(@NotBlank @Email String email, @NotBlank @ValidPassword String password) {
}
