package com.example.relay.deliveryengine.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class RetryDelayCalculatorTest {

    @Test
    void usesTheInjectedClockAndPositiveJitter() {
        RetryDelayCalculator calculator = calculatorAt("2026-09-20T12:00:00Z", Duration.ofSeconds(7));

        assertThat(calculator.nextRetryAt(Duration.ofSeconds(30)))
                .isEqualTo(Instant.parse("2026-09-20T12:00:37Z"));
    }

    @Test
    void quarterJitterKeepsTwoMinuteTierBetweenTwoAndTwoAndAHalfMinutes() {
        assertThat(calculatorWith(Duration.ZERO).nextRetryAt(Duration.ofMinutes(2)))
                .isEqualTo(Instant.parse("2026-09-20T12:02:00Z"));
        assertThat(calculatorWith(Duration.ofSeconds(30)).nextRetryAt(Duration.ofMinutes(2)))
                .isEqualTo(Instant.parse("2026-09-20T12:02:30Z"));
    }

    @Test
    void rejectsConfirmTimeoutThatIsNotStrictlyShorterThanPublicationLease() {
        RetryProperties properties = validProperties();
        properties.setPublishConfirmTimeout(Duration.ofSeconds(10));
        properties.setUnconfirmedReadyGrace(Duration.ofSeconds(10));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publish-confirm-timeout");
    }

    @Test
    void rejectsNegativeJitterFactor() {
        RetryProperties properties = validProperties();
        properties.setJitterFactor(-0.01d);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jitter-factor");
    }

    @Test
    void rejectsNegativeUnconfirmedReadyGrace() {
        RetryProperties properties = validProperties();
        properties.setUnconfirmedReadyGrace(Duration.ofSeconds(-1));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unconfirmed-ready-grace");
    }

    @Test
    void rejectsNonPositiveSchedulingValues() {
        RetryProperties properties = validProperties();
        properties.setSchedulerInterval(Duration.ZERO);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheduler-interval");

        properties = validProperties();
        properties.setSchedulerBatchSize(0);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheduler-batch-size");

        properties = validProperties();
        properties.setDispatcherInterval(Duration.ZERO);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dispatcher-interval");

        properties = validProperties();
        properties.setDispatcherBatchSize(0);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dispatcher-batch-size");

        properties = validProperties();
        properties.setPublishConfirmTimeout(Duration.ZERO);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publish-confirm-timeout");
    }

    private RetryDelayCalculator calculatorAt(String instant, Duration jitter) {
        RetryProperties properties = validProperties();
        properties.setJitterFactor(0.25d);
        return new RetryDelayCalculator(
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC),
                properties,
                maximumInclusive -> jitter);
    }

    private RetryDelayCalculator calculatorWith(Duration jitter) {
        return calculatorAt("2026-09-20T12:00:00Z", jitter);
    }

    private RetryProperties validProperties() {
        return new RetryProperties();
    }
}
