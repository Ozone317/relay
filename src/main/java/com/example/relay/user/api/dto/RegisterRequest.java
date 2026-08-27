package com.example.relay.user.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest (
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8, message = "password must be at least 8 characters long") String password
) {}
