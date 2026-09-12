package com.example.relay.user.api;

import com.example.relay.common.ratelimit.ClientIpResolver;
import com.example.relay.user.api.dto.PasswordResetConfirmDto;
import com.example.relay.user.api.dto.PasswordResetRequestDto;
import com.example.relay.user.api.dto.PasswordResetResponseDto;
import com.example.relay.user.application.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/password-reset")
public class PasswordResetController {

    private static final String GENERIC_REQUEST_MESSAGE =
            "If an account exists for this email, a password reset link has been sent.";

    private final PasswordResetService passwordResetService;
    private final ClientIpResolver clientIpResolver;

    public PasswordResetController(PasswordResetService passwordResetService, ClientIpResolver clientIpResolver) {
        this.passwordResetService = passwordResetService;
        this.clientIpResolver = clientIpResolver;
    }

    /**
     * Always 200 with the same body, regardless of whether the email exists, is on cooldown, is over its hourly cap, or
     * Redis is down - see the design spec Section 3/6. Never 429.
     */
    @PostMapping("/request")
    public ResponseEntity<PasswordResetResponseDto> request(@Valid @RequestBody PasswordResetRequestDto request,
            HttpServletRequest httpRequest) {
        passwordResetService.requestReset(request.email(), clientIpResolver.resolve(httpRequest));
        return ResponseEntity.ok(new PasswordResetResponseDto(GENERIC_REQUEST_MESSAGE));
    }

    @PostMapping("/confirm")
    public ResponseEntity<PasswordResetResponseDto> confirm(@Valid @RequestBody PasswordResetConfirmDto request) {
        passwordResetService.confirmReset(request.token(), request.newPassword());
        return ResponseEntity.ok(new PasswordResetResponseDto("Password reset successful."));
    }
}
