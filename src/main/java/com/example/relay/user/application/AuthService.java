package com.example.relay.user.application;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.example.relay.common.security.JwtService;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.UserAlreadyExistsException;
import com.example.relay.user.infrastructure.UserRepository;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, AuthenticationManager authenticationManager, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    public String register(String email, String password) {
        // Check if the user already exists
        if (userRepository.findByEmail(email).isPresent()) {
            throw new UserAlreadyExistsException("User with email " + email + " already exists.");
        }

        // Hash the password
        String hashedPassword = passwordEncoder.encode(password);

        // Create and save the new user
        User user = new User(email, hashedPassword);
        userRepository.save(user);

        // Generate JWT token
        String token = jwtService.generateToken(user.getEmail(), user.getId());

        return token;
    }

    public String login(String email, String rawPassword) throws BadCredentialsException {
        // Authenticate the user
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, rawPassword));
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
        // Generate JWT token
        String token = jwtService.generateToken(user.getEmail(), user.getId());

        return token;
    }
}
