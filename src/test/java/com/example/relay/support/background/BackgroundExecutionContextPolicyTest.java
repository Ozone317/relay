package com.example.relay.support.background;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.relay.support.SharedPostgresContainer;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.containers.RabbitMQContainer;

class BackgroundExecutionContextPolicyTest {

    private static final RabbitMQContainer RABBIT = startRabbit();

    private static final Set<ScheduledDescriptor> PRODUCTION_SCHEDULED_METHODS = Set.of(
            new ScheduledDescriptor("RetryScheduler", "scheduledReleaseDueRetries"),
            new ScheduledDescriptor("ReadyWorkDispatcher", "scheduledDispatch"),
            new ScheduledDescriptor("ReconciliationSweeper", "scheduledSweep"),
            new ScheduledDescriptor("PasswordResetTokenCleanupTask", "cleanup"),
            new ScheduledDescriptor("PasswordResetEmailRecoverySweeper", "sweep"));

    private static final Set<ListenerDescriptor> PRODUCTION_LISTENERS = Set.of(
            new ListenerDescriptor("DeliveryWorker", "onMessage", "deliveryWorker", "deliveryListenerContainerFactory"),
            new ListenerDescriptor("DeadLetterNotifier", "onMessage", "deadLetterNotifier",
                    "rabbitListenerContainerFactory"),
            new ListenerDescriptor("EmailDispatchConsumer", "onMessage", "emailDispatchConsumer",
                    "rabbitListenerContainerFactory"));

    @AfterAll
    static void stopRabbit() {
        RABBIT.stop();
    }

    @Nested
    @SpringBootTest
    class OrdinaryContext implements SharedPostgresContainer {

        @DynamicPropertySource
        static void attemptToEnableRabbitListeners(DynamicPropertyRegistry registry) {
            registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> true);
        }

        @Autowired
        private ApplicationContext applicationContext;

        @Autowired
        private RabbitListenerEndpointRegistry listenerRegistry;

        @Test
        void ordinaryContextRegistersNoAutonomousScheduledCallbacks() {
            assertEquals(PRODUCTION_SCHEDULED_METHODS, scheduledInventory(applicationContext),
                    "production @Scheduled inventory changed; classify the new callback under P00");
            assertFalse(applicationContext
                    .containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
            long registeredTasks = applicationContext.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                    .mapToLong(holder -> holder.getScheduledTasks().size())
                    .sum();
            assertEquals(0, registeredTasks, "ordinary context registered autonomous scheduled tasks");
        }

        @Test
        void ordinaryContextHasNoRunningProductionRabbitListenerContainers() {
            assertEquals(PRODUCTION_LISTENERS, rabbitListenerInventory(applicationContext),
                    "production @RabbitListener inventory changed; verify its factory obeys P00 suppression");
            assertEquals(productionListenerIds(), listenerRegistry.getListenerContainerIds(),
                    "Rabbit registry contains an unexpected production listener endpoint");
            PRODUCTION_LISTENERS.forEach(descriptor -> {
                MessageListenerContainer container = listenerRegistry.getListenerContainer(descriptor.listenerId());
                assertNotNull(container, () -> "missing production listener container " + descriptor.listenerId());
                assertFalse(container.isRunning(),
                        () -> "ordinary context started production listener " + descriptor.listenerId() + " via "
                                + descriptor.containerFactory());
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
            assertProductionListenersRunning(applicationContext, listenerRegistry);
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
            assertProductionListenersRunning(applicationContext, listenerRegistry);
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

    private static void assertProductionListenersRunning(
            ApplicationContext applicationContext, RabbitListenerEndpointRegistry registry) {
        assertEquals(PRODUCTION_LISTENERS, rabbitListenerInventory(applicationContext),
                "production @RabbitListener inventory changed; classify the new listener under P00");
        assertEquals(productionListenerIds(), registry.getListenerContainerIds(),
                "Rabbit registry contains an unexpected production listener endpoint");
        PRODUCTION_LISTENERS.forEach(descriptor -> {
            MessageListenerContainer container = registry.getListenerContainer(descriptor.listenerId());
            assertNotNull(container, () -> "missing production listener container " + descriptor.listenerId());
            if (!container.isRunning()) {
                fail("opt-in did not start production listener " + descriptor.listenerId() + " via "
                        + descriptor.containerFactory());
            }
        });
    }

    private static Set<ScheduledDescriptor> scheduledInventory(ApplicationContext applicationContext) {
        return applicationMethods(applicationContext).stream()
                .filter(method -> !AnnotatedElementUtils.getMergedRepeatableAnnotations(method, Scheduled.class)
                        .isEmpty())
                .map(method -> new ScheduledDescriptor(method.getDeclaringClass().getSimpleName(), method.getName()))
                .collect(Collectors.toSet());
    }

    private static Set<ListenerDescriptor> rabbitListenerInventory(ApplicationContext applicationContext) {
        return applicationTypes(applicationContext).stream()
                .flatMap(type -> Stream.concat(
                        AnnotatedElementUtils.getMergedRepeatableAnnotations(type, RabbitListener.class).stream()
                                .map(listener -> listenerDescriptor(type, "<type>", listener)),
                        List.of(ReflectionUtils.getAllDeclaredMethods(type)).stream()
                                .flatMap(method -> AnnotatedElementUtils
                                        .getMergedRepeatableAnnotations(method, RabbitListener.class)
                                        .stream()
                                        .map(listener -> listenerDescriptor(type, method.getName(), listener)))))
                .collect(Collectors.toSet());
    }

    private static ListenerDescriptor listenerDescriptor(
            Class<?> component, String method, RabbitListener listener) {
        String containerFactory = listener.containerFactory().isBlank()
                ? "rabbitListenerContainerFactory"
                : listener.containerFactory();
        return new ListenerDescriptor(component.getSimpleName(), method, listener.id(), containerFactory);
    }

    private static Set<String> productionListenerIds() {
        return PRODUCTION_LISTENERS.stream().map(ListenerDescriptor::listenerId).collect(Collectors.toSet());
    }

    private static Set<Class<?>> applicationTypes(ApplicationContext applicationContext) {
        return List.of(applicationContext.getBeanDefinitionNames()).stream()
                .map(applicationContext::getType)
                .filter(type -> type != null && type.getPackageName().startsWith("com.example.relay"))
                .map(ClassUtils::getUserClass)
                .collect(Collectors.toSet());
    }

    private static Set<Method> applicationMethods(ApplicationContext applicationContext) {
        return applicationTypes(applicationContext).stream()
                .flatMap(type -> List.of(ReflectionUtils.getAllDeclaredMethods(type)).stream())
                .collect(Collectors.toSet());
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

    private record ScheduledDescriptor(String component, String method) {}

    private record ListenerDescriptor(
            String component, String method, String listenerId, String containerFactory) {}
}
