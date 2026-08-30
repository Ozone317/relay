package com.example.relay.subscription.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.app.domain.App;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.subscription.domain.Subscription;
import com.example.relay.user.domain.User;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

@DataJpaTest
public class SubscriptionRepositoryTest {

    @Autowired
    private TestEntityManager testEntityManager;

    @Autowired
    private SubscriptionRepository underTest;

    @Test
    void findAllByAppIdAndEnvironmentIdAndUserId_returnsAllSubscriptions_whenAppEnvironmentAndUserMatch() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint1 = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Endpoint endpoint2 = new Endpoint("Staging", "https://staging.example.com/webhook", "whsec_2", app);
        Event event1 = new Event("user.created", app);
        Event event2 = new Event("user.deleted", app);
        Subscription sub1 = new Subscription(app, event1, endpoint1);
        Subscription sub2 = new Subscription(app, event2, endpoint1);
        Subscription sub3 = new Subscription(app, event1, endpoint2);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(env);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(endpoint1);
        testEntityManager.persistAndFlush(endpoint2);
        testEntityManager.persistAndFlush(event1);
        testEntityManager.persistAndFlush(event2);
        testEntityManager.persistAndFlush(sub1);
        testEntityManager.persistAndFlush(sub2);
        testEntityManager.persistAndFlush(sub3);

        // Act
        List<Subscription> result =
                underTest.findAllByAppIdAndEnvironmentIdAndUserId(app.getId(), env.getId(), user.getId());

        // Assert
        assertEquals(3, result.size());
    }

    @Test
    void findAllByAppIdAndEnvironmentIdAndEndpointIdAndUserId_returnsAllSubscriptions_whenAppEnvironmentEndpointAndUserMatch() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint1 = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Endpoint endpoint2 = new Endpoint("Staging", "https://staging.example.com/webhook", "whsec_2", app);
        Event event1 = new Event("user.created", app);
        Event event2 = new Event("user.deleted", app);
        Subscription sub1 = new Subscription(app, event1, endpoint1);
        Subscription sub2 = new Subscription(app, event2, endpoint1);
        Subscription sub3 = new Subscription(app, event1, endpoint2);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(env);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(endpoint1);
        testEntityManager.persistAndFlush(endpoint2);
        testEntityManager.persistAndFlush(event1);
        testEntityManager.persistAndFlush(event2);
        testEntityManager.persistAndFlush(sub1);
        testEntityManager.persistAndFlush(sub2);
        testEntityManager.persistAndFlush(sub3);

        // Act
        List<Subscription> result = underTest.findAllByAppIdAndEnvironmentIdAndEndpointIdAndUserId(app.getId(),
                env.getId(), endpoint1.getId(), user.getId());

        // Assert
        assertEquals(2, result.size());
    }

    @Test
    void findByAppIdAndEnvironmentIdAndEventIdAndEndpointIdAndUserId_returnsSubscription_whenAppEnvironmentEventEndpointAndUserMatch() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint1 = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Endpoint endpoint2 = new Endpoint("Staging", "https://staging.example.com/webhook", "whsec_2", app);
        Event event1 = new Event("user.created", app);
        Event event2 = new Event("user.deleted", app);
        Subscription sub1 = new Subscription(app, event1, endpoint1);
        Subscription sub2 = new Subscription(app, event2, endpoint1);
        Subscription sub3 = new Subscription(app, event1, endpoint2);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(env);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(endpoint1);
        testEntityManager.persistAndFlush(endpoint2);
        testEntityManager.persistAndFlush(event1);
        testEntityManager.persistAndFlush(event2);
        testEntityManager.persistAndFlush(sub1);
        testEntityManager.persistAndFlush(sub2);
        testEntityManager.persistAndFlush(sub3);

        // Act
        Optional<Subscription> result = underTest.findByAppIdAndEnvironmentIdAndEventIdAndEndpointIdAndUserId(
                app.getId(), env.getId(), event1.getId(), endpoint1.getId(), user.getId());

        // Assert
        assertFalse(result.isEmpty());
        assertEquals(sub1, result.get());
        assertFalse(sub2.equals(result.get()));
    }

    @Test
    void findAllByAppIdAndEnvironmentIdAndUserId_returnsEmpty_whenUserDoesNotMatch() {
        // Arrange
        User user1 = new User("test@mail.com", "passwordHash");
        User user2 = new User("test2@mail.com", "someOtherHash");
        Environment env = new Environment("Env 1", "Desc 1", user1);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Event event = new Event("user.created", app);
        Subscription sub = new Subscription(app, event, endpoint);

        testEntityManager.persistAndFlush(user1);
        testEntityManager.persistAndFlush(user2);
        testEntityManager.persistAndFlush(env);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(sub);

        // Act
        List<Subscription> result =
                underTest.findAllByAppIdAndEnvironmentIdAndUserId(app.getId(), env.getId(), user2.getId());

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void findAllByAppIdAndEnvironmentIdAndEndpointIdAndUserId_returnsEmpty_whenEndpointDoesNotMatch() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint1 = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Endpoint endpoint2 = new Endpoint("Staging", "https://staging.example.com/webhook", "whsec_2", app);
        Event event = new Event("user.created", app);
        Subscription sub = new Subscription(app, event, endpoint1);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(env);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(endpoint1);
        testEntityManager.persistAndFlush(endpoint2);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(sub);

        // Act
        List<Subscription> result = underTest.findAllByAppIdAndEnvironmentIdAndEndpointIdAndUserId(app.getId(),
                env.getId(), endpoint2.getId(), user.getId());

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void findByAppIdAndEnvironmentIdAndEventIdAndEndpointIdAndUserId_returnsEmpty_whenNoMatchingSubscriptionExists() {
        // Arrange
        User user = new User("test@mail.com", "passwordHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Endpoint endpoint = new Endpoint("Production", "https://example.com/webhook", "whsec_1", app);
        Event event1 = new Event("user.created", app);
        Event event2 = new Event("user.deleted", app);
        Subscription sub = new Subscription(app, event1, endpoint);

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(env);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(event1);
        testEntityManager.persistAndFlush(event2);
        testEntityManager.persistAndFlush(sub);

        // Act
        Optional<Subscription> result = underTest.findByAppIdAndEnvironmentIdAndEventIdAndEndpointIdAndUserId(
                app.getId(), env.getId(), event2.getId(), endpoint.getId(), user.getId());

        // Assert
        assertTrue(result.isEmpty());
    }
}
