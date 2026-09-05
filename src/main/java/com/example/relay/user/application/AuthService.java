package com.example.relay.user.application;

import com.example.relay.common.security.AuthProperties;
import com.example.relay.common.security.JwtService;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.UserAlreadyExistsException;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuthProperties authProperties;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager, JwtService jwtService,
            RefreshTokenService refreshTokenService, AuthProperties authProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.authProperties = authProperties;
    }

    public IssuedTokens register(String email, String password) {
        if (userRepository.findByEmail(email).isPresent()) {
            throw new UserAlreadyExistsException("User with email " + email + " already exists.");
        }

        User user = new User(email, passwordEncoder.encode(password));
        userRepository.save(user);

        return issueFor(user);
    }

    public IssuedTokens login(String email, String rawPassword) throws BadCredentialsException {
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, rawPassword));
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        return issueFor(user);
    }

    public IssuedTokens refresh(String rawRefreshToken) {
        User user = refreshTokenService.validateAndSlide(rawRefreshToken, Instant.now());
        return new IssuedTokens(jwtService.generateToken(user.getEmail(), user.getId()), rawRefreshToken,
                accessTokenTtlSeconds());
    }

    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken, Instant.now());
    }

    public void logoutAll(UUID userId) {
        refreshTokenService.revokeAll(userId, Instant.now());
    }

    private IssuedTokens issueFor(User user) {
        Instant now = Instant.now();
        return new IssuedTokens(jwtService.generateToken(user.getEmail(), user.getId()),
                refreshTokenService.issue(user, now), accessTokenTtlSeconds());
    }

    private long accessTokenTtlSeconds() {
        return authProperties.getAccessTokenTtl().toSeconds();
    }
}
