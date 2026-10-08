package com.example.relay.user.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.relay.user.application.PasswordResetService;
import com.example.relay.user.application.PasswordResetTokenService.RecoveryOutcome;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.support.ScheduledCallbackTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;

/**
 * Unit-level coverage for delegation of candidate scan hints; transactionally authoritative decisions live in
 * PasswordResetTokenService.recoverCandidate.
 */
@Execution(ExecutionMode.SAME_THREAD)
class PasswordResetEmailRecoverySweeperTest {

    private PasswordResetTokenRepository passwordResetTokenRepository;
    private PasswordResetService passwordResetService;
    private PasswordResetEmailRecoveryProperties properties;
    private PasswordResetEmailRecoverySweeper underTest;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        passwordResetTokenRepository = mock(PasswordResetTokenRepository.class);
        passwordResetService = mock(PasswordResetService.class);
        properties = new PasswordResetEmailRecoveryProperties();
        properties.setGrace(Duration.ofSeconds(90));
        properties.setMaxRecoveryWindow(Duration.ofHours(1));

        underTest = new PasswordResetEmailRecoverySweeper(passwordResetTokenRepository, passwordResetService,
                properties, ScheduledCallbackTestSupport.openRunner());

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
    void everyScanHintDelegatesIdsAndDurations_evenWhenObservedChainIsPastItsWindow() {
        PasswordResetToken withinWindow = candidateWithFirstRequestedAt(Instant.now().minusSeconds(120));
        PasswordResetToken pastWindow = candidateWithFirstRequestedAt(Instant.now().minusSeconds(7200));
        stubCandidates(withinWindow, pastWindow);
        when(passwordResetService.issueAndDispatchForRecovery(withinWindow.getId(), withinWindow.getUser().getId(),
                properties.getGrace(), properties.getMaxRecoveryWindow())).thenReturn(RecoveryOutcome.REISSUED);
        when(passwordResetService.issueAndDispatchForRecovery(pastWindow.getId(), pastWindow.getUser().getId(),
                properties.getGrace(), properties.getMaxRecoveryWindow())).thenReturn(RecoveryOutcome.EXHAUSTED);

        underTest.sweep();

        verify(passwordResetService).issueAndDispatchForRecovery(withinWindow.getId(), withinWindow.getUser().getId(),
                properties.getGrace(), properties.getMaxRecoveryWindow());
        verify(passwordResetService).issueAndDispatchForRecovery(pastWindow.getId(), pastWindow.getUser().getId(),
                properties.getGrace(), properties.getMaxRecoveryWindow());
        assertThat(logAppender.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(message -> message.contains("chain retired at bound"));
    }

    @Test
    void lostRaceIsLoggedAsANoopWithoutDamageWarning() {
        PasswordResetToken candidate = candidateWithFirstRequestedAt(Instant.now().minusSeconds(120));
        stubCandidates(candidate);
        when(passwordResetService.issueAndDispatchForRecovery(candidate.getId(), candidate.getUser().getId(),
                properties.getGrace(), properties.getMaxRecoveryWindow())).thenReturn(RecoveryOutcome.LOST_RACE);

        underTest.sweep();

        verify(passwordResetService).issueAndDispatchForRecovery(candidate.getId(), candidate.getUser().getId(),
                properties.getGrace(), properties.getMaxRecoveryWindow());
        assertThat(logAppender.list.stream().map(ILoggingEvent::getLevel))
                .noneMatch(level -> level.isGreaterOrEqual(ch.qos.logback.classic.Level.WARN));
    }
}
