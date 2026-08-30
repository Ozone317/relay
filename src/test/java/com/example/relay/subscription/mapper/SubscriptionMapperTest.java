package com.example.relay.subscription.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.relay.app.domain.App;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.subscription.api.dto.SubscriptionResponseDto;
import com.example.relay.subscription.domain.Subscription;
import com.example.relay.user.domain.User;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SubscriptionMapperTest {

    private SubscriptionMapper underTest;

    @BeforeEach
    void setUp() {
        underTest = new SubscriptionMapper();
    }

    @Test
    void toResponseDto_mapsSubscriptionToSubscriptionResponseDtoAndReturns() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Event event = new Event("user.created", app);
        Subscription subscription = new Subscription(app, event, endpoint);

        // Act
        SubscriptionResponseDto result = underTest.toResponseDto(subscription);

        // Assert
        assertEquals(subscription.getId(), result.id());
        assertEquals(app.getId(), result.appId());
        assertEquals(event.getId(), result.eventId());
        assertEquals(event.getName(), result.eventName());
        assertEquals(endpoint.getId(), result.endpointId());
        assertEquals(subscription.getCreatedAt(), result.createdAt());
    }

    @Test
    void toResponseDtoList_mapsListOfSubscriptionsToListOfSubscriptionResponseDto() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Event event1 = new Event("user.created", app);
        Event event2 = new Event("user.deleted", app);
        List<Subscription> subscriptions =
                List.of(new Subscription(app, event1, endpoint), new Subscription(app, event2, endpoint));

        // Act
        List<SubscriptionResponseDto> result = underTest.toResponseDtoList(subscriptions);

        // Assert
        assertEquals(subscriptions.size(), result.size());
        assertEquals(subscriptions.get(0).getId(), result.get(0).id());
        assertEquals(subscriptions.get(1).getId(), result.get(1).id());
        assertEquals(subscriptions.get(0).getEvent().getName(), result.get(0).eventName());
        assertEquals(subscriptions.get(1).getEvent().getName(), result.get(1).eventName());
    }
}
