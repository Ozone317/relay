package com.example.relay.support.background;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.relay.deliveryengine.deadletter.DeadLetterNotifier;
import com.example.relay.deliveryengine.worker.DeliveryWorker;
import com.example.relay.email.EmailDispatchConsumer;
import com.example.relay.support.SharedPostgresContainer;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.containers.RabbitMQContainer;

class BackgroundExecutionContextPolicyTest {

    private static final RabbitMQContainer RABBIT = startRabbit();

    private static final Map<String, ListenerDescriptor> PRODUCTION_LISTENERS = Map.of("deliveryWorker",
            new ListenerDescriptor(DeliveryWorker.class, "deliveryListenerContainerFactory"), "deadLetterNotifier",
            new ListenerDescriptor(DeadLetterNotifier.class, "rabbitListenerContainerFactory"), "emailDispatchConsumer",
            new ListenerDescriptor(EmailDispatchConsumer.class, "rabbitListenerContainerFactory"));

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = "spring.rabbitmq.listener.simple.auto-startup=true")
    class OrdinaryContext implements SharedPostgresContainer {

        @Autowired
        private ApplicationContext applicationContext;

        @Autowired
        private RabbitListenerEndpointRegistry listenerRegistry;

        @Test
        void ordinaryContextRegistersNoAutonomousScheduledCallbacks() {
            assertFalse(applicationContext
                    .containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
        }

        @Test
        void ordinaryContextHasNoRunningProductionRabbitListenerContainers() {
            PRODUCTION_LISTENERS.forEach((listenerId, descriptor) -> {
                assertEquals(descriptor.containerFactory(), actualContainerFactory(descriptor.component()),
                        () -> "listener factory inventory changed for " + listenerId);
                MessageListenerContainer container = listenerRegistry.getListenerContainer(listenerId);
                assertNotNull(container, () -> "missing production listener container " + listenerId);
                assertFalse(container.isRunning(), () -> "ordinary context started production listener " + listenerId
                        + " via " + descriptor.containerFactory());
            });
        }
    }

    @Nested
    @SpringJUnitConfig(SchedulingProbeConfiguration.class)
    @TestPropertySource(properties = "p00.probe.delay=10ms")
    @EnableTestBackgroundExecution(TestBackgroundComponent.SCHEDULING)
    class SchedulingOptInContext {

        @Autowired
        private CountDownLatch scheduledCallback;

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        void schedulingOptInRunsARealScheduledCallback() {
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(0, scheduledCallback.getCount()));
            assertFalse(applicationContext
                    .containsBean("org.springframework.amqp.rabbit.config.internalRabbitListenerEndpointRegistry"));
        }
    }

    @Nested
    @SpringBootTest
    @EnableTestBackgroundExecution(TestBackgroundComponent.RABBIT_LISTENERS)
    class RabbitListenerOptInContext implements SharedPostgresContainer {

        @Autowired
        private ApplicationContext applicationContext;

        @Autowired
        private RabbitListenerEndpointRegistry listenerRegistry;

        @DynamicPropertySource
        static void rabbitProperties(DynamicPropertyRegistry registry) {
            registerRabbitProperties(registry);
        }

        @Test
        void rabbitListenerOptInStartsEveryProductionListenerButNotScheduling() {
            assertProductionListenersRunning(listenerRegistry);
            assertFalse(applicationContext
                    .containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
        }
    }

    @Nested
    @SpringBootTest
    @EnableTestBackgroundExecution({TestBackgroundComponent.SCHEDULING, TestBackgroundComponent.RABBIT_LISTENERS})
    class CombinedOptInContext implements SharedPostgresContainer {

        @Autowired
        private ApplicationContext applicationContext;

        @Autowired
        private RabbitListenerEndpointRegistry listenerRegistry;

        @DynamicPropertySource
        static void rabbitProperties(DynamicPropertyRegistry registry) {
            registerRabbitProperties(registry);
        }

        @Test
        void combinedOptInKeepsBothProductionActivationPaths() {
            assertProductionListenersRunning(listenerRegistry);
            assertTrue(applicationContext
                    .containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
        }
    }

    @Test
    void customizerCacheIdentityDependsOnlyOnCapabilityBits() {
        BackgroundExecutionContextCustomizerFactory factory = new BackgroundExecutionContextCustomizerFactory();
        List<ContextConfigurationAttributes> noConfigurationAttributes = List.of();

        ContextCustomizer ordinaryOne =
                factory.createContextCustomizer(OrdinaryFixtureOne.class, noConfigurationAttributes);
        ContextCustomizer ordinaryTwo =
                factory.createContextCustomizer(OrdinaryFixtureTwo.class, noConfigurationAttributes);
        ContextCustomizer scheduling =
                factory.createContextCustomizer(SchedulingFixture.class, noConfigurationAttributes);
        ContextCustomizer listeners = factory.createContextCustomizer(ListenerFixture.class, noConfigurationAttributes);
        ContextCustomizer combined = factory.createContextCustomizer(CombinedFixture.class, noConfigurationAttributes);
        ContextCustomizer composed = factory.createContextCustomizer(ComposedFixture.class, noConfigurationAttributes);

        assertEquals(ordinaryOne, ordinaryTwo);
        assertEquals(ordinaryOne.hashCode(), ordinaryTwo.hashCode());
        assertNotEquals(ordinaryOne, scheduling);
        assertNotEquals(ordinaryOne, listeners);
        assertNotEquals(scheduling, listeners);
        assertNotEquals(scheduling, combined);
        assertNotEquals(listeners, combined);
        assertEquals(scheduling, composed);
    }

    private static String actualContainerFactory(Class<?> component) {
        for (Method method : component.getDeclaredMethods()) {
            RabbitListener listener = method.getAnnotation(RabbitListener.class);
            if (listener != null) {
                return listener.containerFactory().isBlank() ? "rabbitListenerContainerFactory"
                        : listener.containerFactory();
            }
        }
        throw new AssertionError("No @RabbitListener found on " + component.getName());
    }

    private static RabbitMQContainer startRabbit() {
        RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.3.6-management");
        rabbit.start();
        return rabbit;
    }

    private static void registerRabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    private static void assertProductionListenersRunning(RabbitListenerEndpointRegistry registry) {
        PRODUCTION_LISTENERS.forEach((listenerId, descriptor) -> {
            MessageListenerContainer container = registry.getListenerContainer(listenerId);
            assertNotNull(container, () -> "missing production listener container " + listenerId);
            if (!container.isRunning()) {
                fail("opt-in did not start production listener " + listenerId + " via "
                        + descriptor.containerFactory());
            }
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingProbeConfiguration {

        @Bean
        CountDownLatch scheduledCallback() {
            return new CountDownLatch(1);
        }

        @Bean
        SchedulingProbe schedulingProbe(CountDownLatch scheduledCallback) {
            return new SchedulingProbe(scheduledCallback);
        }
    }

    static final class SchedulingProbe {

        private final CountDownLatch scheduledCallback;

        SchedulingProbe(CountDownLatch scheduledCallback) {
            this.scheduledCallback = scheduledCallback;
        }

        @Scheduled(fixedDelayString = "${p00.probe.delay}")
        void tick() {
            scheduledCallback.countDown();
        }
    }

    private static class OrdinaryFixtureOne {
    }

    private static class OrdinaryFixtureTwo {
    }

    @EnableTestBackgroundExecution(TestBackgroundComponent.SCHEDULING)
    private static class SchedulingFixture {
    }

    @EnableTestBackgroundExecution(TestBackgroundComponent.RABBIT_LISTENERS)
    private static class ListenerFixture {
    }

    @EnableTestBackgroundExecution({TestBackgroundComponent.SCHEDULING, TestBackgroundComponent.RABBIT_LISTENERS})
    private static class CombinedFixture {
    }

    @SchedulingEnabledTest
    private static class ComposedFixture {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @EnableTestBackgroundExecution(TestBackgroundComponent.SCHEDULING)
    private @interface SchedulingEnabledTest {
    }

    private record ListenerDescriptor(Class<?> component, String containerFactory) {
    }
}
