package com.example.relay.user.application;

import com.example.relay.common.security.AuthProperties;
import com.example.relay.common.security.JwtService;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.UserAlreadyExistsException;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Instant;
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

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager, JwtService jwtService, RefreshTokenService refreshTokenService,
            AuthProperties authProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.authProperties = authProperties;
    }

    /**
     * Registers a user and opens their first session.
     *
     * <p>
     * {@code @Transactional} is load-bearing: {@link RefreshTokenService#issue} is itself transactional with the
     * default REQUIRED propagation, so it joins this transaction instead of opening a second one. Without it, a failing
     * session insert would strand an already-committed user row, and the caller's retry would then get a permanent 409
     * for an account they never successfully created.
     *
     * <p>
     * Password hashing runs inside the transaction. bcrypt at cost 12 holds a pooled connection for the duration of the
     * hash; accepted deliberately at this scale rather than overlooked.
     */
    @Transactional
    public IssuedTokens register(String email, String password) {
        if (userRepository.findByEmail(email).isPresent()) {
            throw new UserAlreadyExistsException("User with email " + email + " already exists.");
        }

        User user = new User(email, passwordEncoder.encode(password));
        try {
            // saveAndFlush, not save: inside a transaction save() may defer the INSERT until
            // commit, which happens after this method returns - the constraint violation would
            // then be thrown past this catch block and reach the client as a 500.
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Lost the check-then-insert race against a concurrent registration for the same
            // address. The lookup above is only a fast path; users.email UNIQUE is the authority.
            // Deliberately the same message as the fast path: which caller lost a database race
            // is not something the loser gets to learn.
            throw new UserAlreadyExistsException("User with email " + email + " already exists.");
        }

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
