package com.example.relay.user.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.relay.user.application.PasswordResetService;
import com.example.relay.user.application.PasswordResetTokenService;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;

/**
 * Unit-level coverage for sweep()'s within-window-vs-give-up branching, deliberately separate from
 * PasswordResetEmailRecoverySweeperIntegrationTest's real-timing/real-Postgres round trips - simulating "the give-up
 * update lost its race" (a concurrent dispatch confirmation or user consumption won first) requires controlling
 * PasswordResetTokenService#giveUpOnRecovery's return value directly, which only a mocked dependency makes practical.
 */
class PasswordResetEmailRecoverySweeperTest {

    private PasswordResetTokenRepository passwordResetTokenRepository;
    private PasswordResetService passwordResetService;
    private PasswordResetTokenService passwordResetTokenService;
    private PasswordResetEmailRecoveryProperties properties;
    private PasswordResetEmailRecoverySweeper underTest;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        passwordResetTokenRepository = mock(PasswordResetTokenRepository.class);
        passwordResetService = mock(PasswordResetService.class);
        passwordResetTokenService = mock(PasswordResetTokenService.class);
        properties = new PasswordResetEmailRecoveryProperties();
        properties.setGrace(Duration.ofSeconds(90));
        properties.setMaxRecoveryWindow(Duration.ofHours(1));

        underTest = new PasswordResetEmailRecoverySweeper(passwordResetTokenRepository, passwordResetService,
                passwordResetTokenService, properties);

        logAppender = new ListAppender<>();
        logAppender.start();
        sweeperLogger().addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        sweeperLogger().detachAppender(logAppender);
    }

    private Logger sweeperLogger() {
        return (Logger) LoggerFactory.getLogger(PasswordResetEmailRecoverySweeper.class);
    }

    private PasswordResetToken candidateWithFirstRequestedAt(Instant firstRequestedAt) {
        User user = new User("sweeper-unit-test@example.com", "hash");
        Instant now = Instant.now();
        return new PasswordResetToken(user, "hash", now.plusSeconds(1800), now, firstRequestedAt);
    }

    private void stubCandidates(PasswordResetToken... candidates) {
        when(passwordResetTokenRepository.findByResetEmailDispatchedAtIsNullAndUsedAtIsNullAndExpiresAtAfterAndUpdatedAtBefore(
                any(), any(), any(Limit.class))).thenReturn(List.of(candidates));
    }

    @Test
    void withinWindowCandidate_isReissued_giveUpIsNeverCalled() {
        PasswordResetToken candidate = candidateWithFirstRequestedAt(Instant.now().minusSeconds(120));
        stubCandidates(candidate);

        underTest.sweep();

        verify(passwordResetService).issueAndDispatchForRecovery(candidate.getUser(), candidate.getFirstRequestedAt());
        verify(passwordResetTokenService, never()).giveUpOnRecovery(any(), any());
    }

    @Test
    void pastWindowCandidate_givesUp_andLogsWhenTheUpdateWins() {
        PasswordResetToken candidate = candidateWithFirstRequestedAt(Instant.now().minusSeconds(7200));
        stubCandidates(candidate);
        when(passwordResetTokenService.giveUpOnRecovery(any(), any())).thenReturn(true);

        underTest.sweep();

        verify(passwordResetService, never()).issueAndDispatchForRecovery(any(), any());
        verify(passwordResetTokenService).giveUpOnRecovery(eq(candidate.getId()), any());
        assertThat(logAppender.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(message -> message.contains("Giving up"));
    }

    @Test
    void pastWindowCandidate_neverReissues_andDoesNotClaimGiveUp_whenTheUpdateLosesTheRace() {
        PasswordResetToken candidate = candidateWithFirstRequestedAt(Instant.now().minusSeconds(7200));
        stubCandidates(candidate);
        when(passwordResetTokenService.giveUpOnRecovery(any(), any())).thenReturn(false);

        underTest.sweep();

        verify(passwordResetService, never()).issueAndDispatchForRecovery(any(), any());
        assertThat(logAppender.list.stream().map(ILoggingEvent::getFormattedMessage))
                .noneMatch(message -> message.contains("Giving up"));
    }
}
