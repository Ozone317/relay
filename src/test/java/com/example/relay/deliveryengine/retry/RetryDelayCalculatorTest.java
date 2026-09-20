package com.example.relay.deliveryengine.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
    void rejectsBaseDelayWhoseNanosecondsCannotBeRepresented() {
        assertThatThrownBy(() -> calculatorWith(Duration.ZERO).nextRetryAt(Duration.ofSeconds(Long.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base-delay");
    }

    @Test
    void rejectsFiniteJitterFactorWhoseBoundCannotBeRepresented() {
        RetryProperties properties = validProperties();
        properties.setJitterFactor(Double.MAX_VALUE);
        RetryDelayCalculator calculator = new RetryDelayCalculator(
                Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC),
                properties,
                maximumInclusive -> Duration.ZERO);

        assertThatThrownBy(() -> calculator.nextRetryAt(Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jitter-bound");
    }

    @Test
    void samplesTheMaximumNanosecondBoundWithoutOverflow() {
        Duration maximumInclusive = Duration.ofNanos(Long.MAX_VALUE);

        Duration jitter = new ThreadLocalRetryJitterSource().next(maximumInclusive);

        assertThat(jitter).isBetween(Duration.ZERO, maximumInclusive);
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
    void rejectsConfirmTimeoutAndGraceThatCannotBeRepresentedAsWholeMilliseconds() {
        RetryProperties properties = validProperties();
        properties.setPublishConfirmTimeout(Duration.ofNanos(250_000));
        properties.setUnconfirmedReadyGrace(Duration.ofNanos(500_000));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unconfirmed-ready-grace");
    }

    @Test
    void rejectsConfirmTimeoutThatOverflowsMillisecondConversion() {
        RetryProperties properties = validProperties();
        properties.setPublishConfirmTimeout(Duration.ofSeconds(Long.MAX_VALUE));

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonPositiveProperties")
    void rejectsZeroAndNegativeValuesForEveryPositiveProperty(
            String propertyName,
            Consumer<RetryProperties> setZero,
            Consumer<RetryProperties> setNegative) {
        assertInvalidProperty(propertyName, setZero);
        assertInvalidProperty(propertyName, setNegative);
    }

    private void assertInvalidProperty(String propertyName, Consumer<RetryProperties> setter) {
        RetryProperties properties = validProperties();
        setter.accept(properties);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(propertyName);
    }

    private static Stream<Arguments> nonPositiveProperties() {
        return Stream.of(
                Arguments.of("scheduler-interval",
                        (Consumer<RetryProperties>) properties -> properties.setSchedulerInterval(Duration.ZERO),
                        (Consumer<RetryProperties>) properties -> properties.setSchedulerInterval(Duration.ofSeconds(-1))),
                Arguments.of("scheduler-batch-size",
                        (Consumer<RetryProperties>) properties -> properties.setSchedulerBatchSize(0),
                        (Consumer<RetryProperties>) properties -> properties.setSchedulerBatchSize(-1)),
                Arguments.of("dispatcher-interval",
                        (Consumer<RetryProperties>) properties -> properties.setDispatcherInterval(Duration.ZERO),
                        (Consumer<RetryProperties>) properties -> properties.setDispatcherInterval(Duration.ofSeconds(-1))),
                Arguments.of("dispatcher-batch-size",
                        (Consumer<RetryProperties>) properties -> properties.setDispatcherBatchSize(0),
                        (Consumer<RetryProperties>) properties -> properties.setDispatcherBatchSize(-1)),
                Arguments.of("publish-confirm-timeout",
                        (Consumer<RetryProperties>) properties -> properties.setPublishConfirmTimeout(Duration.ZERO),
                        (Consumer<RetryProperties>) properties -> properties.setPublishConfirmTimeout(Duration.ofSeconds(-1))));
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
