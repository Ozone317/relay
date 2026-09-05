package com.example.relay.user.application;

/**
 * The pair issued at login: the access token for the JSON body, and the RAW (unhashed) refresh
 * token for the Set-Cookie header. Only the hash of the refresh token is ever persisted.
 */
public record IssuedTokens(String accessToken, String rawRefreshToken, long expiresIn) {
}
