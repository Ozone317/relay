package com.example.relay.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtService {

    private final AuthProperties authProperties;
    private final String secret;

    public JwtService(AuthProperties authProperties, @Value("${jwt.secret}") String secret) {
        this.authProperties = authProperties;
        this.secret = secret;
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    private Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
    }

    public String generateToken(String email, UUID userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + authProperties.getAccessTokenTtl().toMillis());

        return Jwts.builder().subject(email).claim("userId", userId).issuedAt(now).expiration(expiry)
                .signWith(getSigningKey()).compact();
    }

    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    public UUID extractUserId(String token) {
        String userIdString = parseClaims(token).get("userId", String.class);
        return UUID.fromString(userIdString);
    }

    public boolean isValid(String token) {
        try {
            return !parseClaims(token).getExpiration().before(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
