package com.example.relay.user.api;

import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.common.security.RefreshCookieFactory;
import com.example.relay.user.api.dto.AuthResponse;
import com.example.relay.user.api.dto.LoginRequest;
import com.example.relay.user.api.dto.RegisterRequest;
import com.example.relay.user.application.AuthService;
import com.example.relay.user.application.IssuedTokens;
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

    private final AuthService authService;
    private final RefreshCookieFactory refreshCookieFactory;

    public AuthController(AuthService authService, RefreshCookieFactory refreshCookieFactory) {
        this.authService = authService;
        this.refreshCookieFactory = refreshCookieFactory;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return respondWithTokens(HttpStatus.CREATED, authService.register(request.email(), request.password()));
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
