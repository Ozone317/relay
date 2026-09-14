package com.example.relay.user.api;

import com.example.relay.common.ratelimit.ClientIpResolver;
import com.example.relay.user.api.dto.EmailVerificationConfirmDto;
import com.example.relay.user.api.dto.EmailVerificationRequestDto;
import com.example.relay.user.api.dto.EmailVerificationResponseDto;
import com.example.relay.user.application.EmailVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/email-verification")
public class EmailVerificationController {

    private static final String RESEND_ACCEPTED_MESSAGE =
            "If an account needs verifying, a verification email has been sent.";

    private final EmailVerificationService emailVerificationService;
    private final ClientIpResolver clientIpResolver;

    public EmailVerificationController(EmailVerificationService emailVerificationService,
            ClientIpResolver clientIpResolver) {
        this.emailVerificationService = emailVerificationService;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/verify")
    public ResponseEntity<EmailVerificationResponseDto> verify(@Valid @RequestBody EmailVerificationConfirmDto request) {
        emailVerificationService.verify(request.token(), request.password());
        return ResponseEntity.ok(new EmailVerificationResponseDto("Email verified successfully."));
    }

    @PostMapping("/resend")
    public ResponseEntity<EmailVerificationResponseDto> resend(@Valid @RequestBody EmailVerificationRequestDto request,
            HttpServletRequest httpRequest) {
        emailVerificationService.resend(request.email(), clientIpResolver.resolve(httpRequest));
        return ResponseEntity.ok(new EmailVerificationResponseDto(RESEND_ACCEPTED_MESSAGE));
    }
}
