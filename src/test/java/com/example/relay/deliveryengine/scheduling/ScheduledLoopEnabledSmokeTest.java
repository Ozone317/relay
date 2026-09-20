package com.example.relay.deliveryengine.scheduling;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.relay.attempt.infrastructure.ReadyWorkRepository;
import com.example.relay.deliveryengine.config.RetrySchedulingConfig;
import com.example.relay.deliveryengine.dispatcher.ReadyWorkDispatcher;
import com.example.relay.deliveryengine.publisher.ReadyTaskPublisher;
import com.example.relay.deliveryengine.retry.RetryProperties;
import com.example.relay.deliveryengine.retry.RetryScheduler;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.annotation.DirtiesContext;

@SpringJUnitConfig(ScheduledLoopEnabledSmokeTest.TestConfig.class)
@TestPropertySource(properties = {
        "relay.retry.scheduling-enabled=true",
        "relay.retry.scheduler-interval=25ms",
        "relay.retry.dispatcher-interval=25ms",
        "relay.retry.scheduler-batch-size=100",
        "relay.retry.dispatcher-batch-size=100"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScheduledLoopEnabledSmokeTest {

    @Autowired
    private ReadyWorkRepository readyWorkRepository;

    @BeforeEach
    void setUp() {
        when(readyWorkRepository.promoteDueScheduled(anyInt())).thenReturn(List.of());
        when(readyWorkRepository.claimUnpublishedReady(any(), any(), anyInt())).thenReturn(List.of());
    }

    @Test
    void enabledSpringSchedulesInvokeRetryAndDispatcherWork() {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            verify(readyWorkRepository, atLeastOnce()).promoteDueScheduled(100);
            verify(readyWorkRepository, atLeastOnce()).claimUnpublishedReady(
                    any(), eq(Duration.ofSeconds(10)), eq(100));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableConfigurationProperties(RetryProperties.class)
    @Import({RetryScheduler.class, ReadyWorkDispatcher.class, RetrySchedulingConfig.class})
    static class TestConfig {

        @Bean
        ReadyWorkRepository readyWorkRepository() {
            return mock(ReadyWorkRepository.class);
        }

        @Bean
        ReadyTaskPublisher readyTaskPublisher() {
            return mock(ReadyTaskPublisher.class);
        }
    }
}
