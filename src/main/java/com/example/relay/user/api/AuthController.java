package com.example.relay.user.api;

import com.example.relay.common.ratelimit.ClientIpResolver;
import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.common.security.RefreshCookieFactory;
import com.example.relay.user.api.dto.AuthResponse;
import com.example.relay.user.api.dto.LoginRequest;
import com.example.relay.user.api.dto.RegisterRequest;
import com.example.relay.user.api.dto.RegisterResponseDto;
import com.example.relay.user.application.AuthService;
import com.example.relay.user.application.EmailVerificationService;
import com.example.relay.user.application.IssuedTokens;
import com.example.relay.user.application.RegisteredUser;
import com.example.relay.user.exception.ConcurrentRegistrationRaceLostException;
import com.example.relay.user.exception.ExistingUnverifiedAccountException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String REGISTRATION_ACCEPTED_MESSAGE =
            "Registration successful. Check your email to verify your account.";

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;
    private final RefreshCookieFactory refreshCookieFactory;
    private final ClientIpResolver clientIpResolver;

    public AuthController(AuthService authService, EmailVerificationService emailVerificationService,
            RefreshCookieFactory refreshCookieFactory, ClientIpResolver clientIpResolver) {
        this.authService = authService;
        this.emailVerificationService = emailVerificationService;
        this.refreshCookieFactory = refreshCookieFactory;
        this.clientIpResolver = clientIpResolver;
    }

    /**
     * Deliberately the first controller method in this codebase with a business-meaningful branch in it - see the
     * design spec Section 5 for why. AuthService.register()'s transaction never performs a Redis call or a RabbitMQ
     * publish for the existing-unverified case; those only happen here, in dispatchInitial/resend, entirely outside
     * any transaction. Both outcomes return the identical REGISTRATION_ACCEPTED_MESSAGE - never two independent
     * literals.
     */
    @PostMapping("/register")
    public ResponseEntity<RegisterResponseDto> register(@Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {
        try {
            RegisteredUser result = authService.register(request.email(), request.password());
            emailVerificationService.dispatchInitial(result.user(), result.issuedToken());
        } catch (ExistingUnverifiedAccountException ex) {
            emailVerificationService.resend(ex.getEmail(), clientIpResolver.resolve(httpRequest));
        } catch (ConcurrentRegistrationRaceLostException ignored) {
            // The concurrent winner already created and will dispatch the only initial token.
            // Returning the shared response preserves non-enumerating external behavior.
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(new RegisterResponseDto(REGISTRATION_ACCEPTED_MESSAGE));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        return respondWithTokens(HttpStatus.OK, authService.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = RefreshCookieFactory.COOKIE_NAME, required = false) String rawRefreshToken) {
        return respondWithTokens(HttpStatus.OK, authService.refresh(rawRefreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshCookieFactory.COOKIE_NAME, required = false) String rawRefreshToken) {
        authService.logout(rawRefreshToken);
        return clearedCookieResponse();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal AuthenticatedUser user) {
        authService.logoutAll(user.getId());
        return clearedCookieResponse();
    }

    private ResponseEntity<AuthResponse> respondWithTokens(HttpStatus status, IssuedTokens tokens) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshCookieFactory.build(tokens.rawRefreshToken()).toString())
                .body(new AuthResponse(tokens.accessToken(), tokens.expiresIn()));
    }

    private ResponseEntity<Void> clearedCookieResponse() {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookieFactory.clear().toString())
                .build();
    }
}
