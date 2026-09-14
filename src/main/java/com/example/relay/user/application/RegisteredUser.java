package com.example.relay.user.application;

import com.example.relay.user.application.EmailVerificationTokenService.IssuedVerificationToken;
import com.example.relay.user.domain.User;

/**
 * register()'s return type for the genuinely-new-user path only - see AuthService.register()'s javadoc for why the
 * existing-unverified path never produces one of these (it throws ExistingUnverifiedAccountException instead).
 */
public record RegisteredUser(User user, IssuedVerificationToken issuedToken) {
}
