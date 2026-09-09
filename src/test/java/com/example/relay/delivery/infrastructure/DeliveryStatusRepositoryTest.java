package com.example.relay.delivery.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.app.domain.App;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.message.domain.Message;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DeliveryStatusRepositoryTest implements SharedPostgresContainer {

    @Autowired
    private DeliveryStatusRepository underTest;

    @Autowired
    private TestEntityManager testEntityManager;

    @Test
    void findAll_withEndpointIdFilter_returnsOnlyMatchingDeliveries_andCorrectAttemptCount() throws Exception {
        User user = new User("status-repo-test@mail.com", "hash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        Endpoint matchingEndpoint = new Endpoint("Match", "https://a.example.com", "whsec_1", app);
        Endpoint otherEndpoint = new Endpoint("Other", "https://b.example.com", "whsec_2", app);
        Message message = new Message(app, event, new ObjectMapper().readTree("{\"amount\":1}"));

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(env);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(matchingEndpoint);
        testEntityManager.persistAndFlush(otherEndpoint);
        testEntityManager.persistAndFlush(message);

        Delivery matchingDelivery = new Delivery(app, message, matchingEndpoint);
        testEntityManager.persistAndFlush(matchingDelivery);
        Attempt firstAttempt = new Attempt(app, message, matchingEndpoint, matchingDelivery, 1);
        firstAttempt.setStatus(AttemptStatus.FAILED_RETRYING);
        testEntityManager.persistAndFlush(firstAttempt);
        Attempt secondAttempt = new Attempt(app, message, matchingEndpoint, matchingDelivery, 2);
        secondAttempt.setStatus(AttemptStatus.SCHEDULED);
        testEntityManager.persistAndFlush(secondAttempt);

        Delivery otherDelivery = new Delivery(app, message, otherEndpoint);
        testEntityManager.persistAndFlush(otherDelivery);
        testEntityManager.persistAndFlush(new Attempt(app, message, otherEndpoint, otherDelivery, 1));

        List<com.example.relay.delivery.domain.DeliveryStatus> results = underTest.findAll(
                DeliveryStatusSpecifications.matching(app.getId(), matchingEndpoint.getId(), null, null, null));

        assertEquals(1, results.size());
        com.example.relay.delivery.domain.DeliveryStatus result = results.get(0);
        assertEquals(matchingDelivery.getId(), result.getDeliveryId());
        assertEquals(AttemptStatus.SCHEDULED, result.getStatus());
        assertEquals(2, result.getAttemptNo());
        assertEquals(2L, result.getAttemptCount());
        assertEquals("payment.completed", result.getEventName());
        assertEquals("Match", result.getEndpointName());
    }

    @Test
    void findAll_withStatusFilter_matchesOnTheDerivedLatestAttemptStatus() throws Exception {
        User user = new User("status-repo-test-2@mail.com", "hash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        Endpoint endpoint = new Endpoint("EP", "https://a.example.com", "whsec_1", app);
        Message message = new Message(app, event, new ObjectMapper().readTree("{\"amount\":1}"));

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(env);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);

        Delivery delivery = new Delivery(app, message, endpoint);
        testEntityManager.persistAndFlush(delivery);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 1);
        attempt.setStatus(AttemptStatus.DEAD);
        testEntityManager.persistAndFlush(attempt);

        List<com.example.relay.delivery.domain.DeliveryStatus> deadResults = underTest.findAll(
                DeliveryStatusSpecifications.matching(app.getId(), null, AttemptStatus.DEAD, null, null));
        List<com.example.relay.delivery.domain.DeliveryStatus> succeededResults = underTest.findAll(
                DeliveryStatusSpecifications.matching(app.getId(), null, AttemptStatus.SUCCEEDED, null, null));

        assertTrue(deadResults.size() == 1);
        assertTrue(succeededResults.isEmpty());
    }
}
