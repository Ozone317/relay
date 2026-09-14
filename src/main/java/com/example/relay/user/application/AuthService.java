package com.example.relay.user.application;

import com.example.relay.common.security.AuthProperties;
import com.example.relay.common.security.JwtService;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.EmailNotVerifiedException;
import com.example.relay.user.exception.ExistingUnverifiedAccountException;
import com.example.relay.user.exception.UserAlreadyExistsException;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuthProperties authProperties;
    private final EmailVerificationTokenService emailVerificationTokenService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager, JwtService jwtService, RefreshTokenService refreshTokenService,
            AuthProperties authProperties, EmailVerificationTokenService emailVerificationTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.authProperties = authProperties;
        this.emailVerificationTokenService = emailVerificationTokenService;
    }

    /**
     * Registers a user and issues their first verification token, in one transaction - it does NOT issue any Bearer
     * tokens (see AuthController.register() for why, and for how the existing-unverified branch is handled outside
     * this method entirely). On an existing row: a verified account still 409s (UserAlreadyExistsException,
     * unchanged); an unverified one throws ExistingUnverifiedAccountException, uniformly from both this fast-path
     * check and the race-loss catch block below - see the design spec Section 5.
     */
    @Transactional
    public RegisteredUser register(String email, String password) {
        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            if (existing.get().isEmailVerified()) {
                throw new UserAlreadyExistsException(email);
            }
            throw new ExistingUnverifiedAccountException(email);
        }

        User user = new User(email, passwordEncoder.encode(password));
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // A race loser against a concurrent brand-new registration is always racing an
            // unverified row - see this method's own javadoc.
            throw new ExistingUnverifiedAccountException(email);
        }

        EmailVerificationTokenService.IssuedVerificationToken issuedToken =
                emailVerificationTokenService.issue(user, Instant.now());
        return new RegisteredUser(user, issuedToken);
    }

    public IssuedTokens login(String email, String rawPassword) throws BadCredentialsException {
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, rawPassword));
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException("Email address is not verified");
        }

        return issueFor(user);
    }

    public IssuedTokens refresh(String rawRefreshToken) {
        User user = refreshTokenService.validateAndSlide(rawRefreshToken, Instant.now());
        return new IssuedTokens(jwtService.generateToken(user.getEmail(), user.getId(), user.isEmailVerified()),
                rawRefreshToken, accessTokenTtlSeconds());
    }

    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken, Instant.now());
    }

    public void logoutAll(UUID userId) {
        refreshTokenService.revokeAll(userId, Instant.now());
    }

    private IssuedTokens issueFor(User user) {
        Instant now = Instant.now();
        return new IssuedTokens(jwtService.generateToken(user.getEmail(), user.getId(), user.isEmailVerified()),
                refreshTokenService.issue(user, now), accessTokenTtlSeconds());
    }

    private long accessTokenTtlSeconds() {
        return authProperties.getAccessTokenTtl().toSeconds();
    }
}
