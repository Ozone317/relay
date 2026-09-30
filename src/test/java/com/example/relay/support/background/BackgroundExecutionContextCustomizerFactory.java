package com.example.relay.support.background;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

public final class BackgroundExecutionContextCustomizerFactory implements ContextCustomizerFactory {

    private static final String PROPERTY_SOURCE_NAME = "p00BackgroundExecutionPolicy";
    private static final String RABBIT_AUTO_STARTUP = "spring.rabbitmq.listener.simple.auto-startup";

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
            List<ContextConfigurationAttributes> configAttributes) {
        EnableTestBackgroundExecution annotation =
                AnnotatedElementUtils.findMergedAnnotation(testClass, EnableTestBackgroundExecution.class);
        Set<TestBackgroundComponent> enabled = annotation == null ? Set.of() : Set.of(annotation.value());
        return new BackgroundExecutionContextCustomizer(enabled.contains(TestBackgroundComponent.SCHEDULING),
                enabled.contains(TestBackgroundComponent.RABBIT_LISTENERS));
    }

    private record BackgroundExecutionContextCustomizer(boolean schedulingEnabled,
            boolean rabbitListenersEnabled) implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            if (!rabbitListenersEnabled) {
                context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(RABBIT_AUTO_STARTUP, false)));
            }
            if (!schedulingEnabled) {
                context.addBeanFactoryPostProcessor(new ScheduledRegistrationSuppressor());
            }
        }
    }

    private static final class ScheduledRegistrationSuppressor implements BeanDefinitionRegistryPostProcessor {

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {}

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
            if (beanFactory instanceof BeanDefinitionRegistry registry && registry
                    .containsBeanDefinition(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)) {
                registry.removeBeanDefinition(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME);
            }
        }
    }
}
