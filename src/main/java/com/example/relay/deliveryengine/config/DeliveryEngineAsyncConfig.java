package com.example.relay.deliveryengine.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class DeliveryEngineAsyncConfig {

    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean(name = "readyWorkConfirmationExecutor")
    ThreadPoolTaskExecutor readyWorkConfirmationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1_000);
        executor.setThreadNamePrefix("relay-ready-confirm-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}
