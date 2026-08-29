package com.example.relay.subscription.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.relay.app.application.AppService;
import com.example.relay.app.domain.App;
import com.example.relay.endpoint.application.EndpointService;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.event.application.EventService;
import com.example.relay.event.domain.Event;
import com.example.relay.environment.domain.Environment;
import com.example.relay.subscription.domain.Subscription;
import com.example.relay.subscription.infrastructure.SubscriptionRepository;
import com.example.relay.user.domain.User;

@ExtendWith(MockitoExtension.class)
public class SubscriptionServiceTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private AppService appService;

    @Mock
    private EventService eventService;

    @Mock
    private EndpointService endpointService;

    @InjectMocks
    private SubscriptionService underTest;

    @Test
    void create_returnsExistingSubscription_whenAlreadySubscribed() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Event event = new Event("user.created", app);
        Subscription existing = new Subscription(app, event, endpoint);

        // Stub
        when(subscriptionRepository.findByAppIdAndEnvironmentIdAndEventIdAndEndpointIdAndUserId(
            app.getId(), env.getId(), event.getId(), endpoint.getId(), user.getId()
        )).thenReturn(Optional.of(existing));

        // Act
        Subscription result = underTest.create(env.getId(), app.getId(), endpoint.getId(), event.getId(), user.getId());

        // Assert
        assertEquals(existing.getId(), result.getId());

        // Verify
        verify(appService, never()).getById(any(), any(), any());
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void create_createsNewSubscription_whenNotAlreadySubscribed() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Event event = new Event("user.created", app);

        // Stubs
        when(subscriptionRepository.findByAppIdAndEnvironmentIdAndEventIdAndEndpointIdAndUserId(
            app.getId(), env.getId(), event.getId(), endpoint.getId(), user.getId()
        )).thenReturn(Optional.empty());
        when(appService.getById(app.getId(), env.getId(), user.getId())).thenReturn(app);
        when(eventService.getById(event.getId(), app.getId(), env.getId(), user.getId())).thenReturn(event);
        when(endpointService.getById(endpoint.getId(), app.getId(), env.getId(), user.getId())).thenReturn(endpoint);
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Subscription result = underTest.create(env.getId(), app.getId(), endpoint.getId(), event.getId(), user.getId());

        // Assert
        assertEquals(app.getId(), result.getApp().getId());
        assertEquals(event.getId(), result.getEvent().getId());
        assertEquals(endpoint.getId(), result.getEndpoint().getId());
    }

    @Test
    void getAll_returnsAllSubscriptions_forGivenEndpoint() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Event event1 = new Event("user.created", app);
        Event event2 = new Event("user.deleted", app);
        List<Subscription> subscriptions = List.of(
            new Subscription(app, event1, endpoint),
            new Subscription(app, event2, endpoint)
        );

        // Stub
        when(subscriptionRepository.findAllByAppIdAndEnvironmentIdAndEndpointIdAndUserId(
            app.getId(), env.getId(), endpoint.getId(), user.getId()
        )).thenReturn(subscriptions);

        // Act
        List<Subscription> result = underTest.getAll(env.getId(), app.getId(), endpoint.getId(), user.getId());

        // Assert
        assertEquals(subscriptions.size(), result.size());
    }

    @Test
    void delete_deletesSubscription_whenItExists() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Event event = new Event("user.created", app);
        Subscription subscription = new Subscription(app, event, endpoint);

        // Stub
        when(subscriptionRepository.findByAppIdAndEnvironmentIdAndEventIdAndEndpointIdAndUserId(
            app.getId(), env.getId(), event.getId(), endpoint.getId(), user.getId()
        )).thenReturn(Optional.of(subscription));

        // Act
        underTest.delete(env.getId(), app.getId(), endpoint.getId(), event.getId(), user.getId());

        // Verify
        verify(subscriptionRepository).delete(subscription);
    }

    @Test
    void delete_doesNothing_whenSubscriptionDoesNotExist() {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Event event = new Event("user.created", app);

        // Stub
        when(subscriptionRepository.findByAppIdAndEnvironmentIdAndEventIdAndEndpointIdAndUserId(
            app.getId(), env.getId(), event.getId(), endpoint.getId(), user.getId()
        )).thenReturn(Optional.empty());

        // Act
        underTest.delete(env.getId(), app.getId(), endpoint.getId(), event.getId(), user.getId());

        // Verify
        verify(subscriptionRepository, never()).delete(any());
    }
}
