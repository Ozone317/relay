package com.example.relay.deliveryengine.config;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.relay.deliveryengine.destination.SystemHostAddressLookup;

@Configuration(proxyBeanMethods = false)
public class DeliveryDnsExecutorConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ThreadPoolExecutor deliveryDnsExecutor(DeliveryDnsProperties properties) {
        ThreadFactory threadFactory = new DaemonThreadFactory();
        return new ThreadPoolExecutor(properties.getMaxConcurrency(), properties.getMaxConcurrency(), 0L,
                java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(properties.getQueueCapacity()), threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "close")
    public SystemHostAddressLookup systemHostAddressLookup(DeliveryDnsProperties properties,
            ThreadPoolExecutor deliveryDnsExecutor) {
        return new SystemHostAddressLookup(properties, deliveryDnsExecutor);
    }

    private static final class DaemonThreadFactory implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "relay-delivery-dns-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
