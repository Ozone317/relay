package com.example.relay.delivery.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.app.domain.App;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.message.domain.Message;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DeliveryRepositoryTest implements SharedPostgresContainer {

    @Autowired
    private DeliveryRepository underTest;

    @Autowired
    private TestEntityManager testEntityManager;

    private App app;
    private Message message;
    private Endpoint endpoint;

    private void arrangeFixtures() throws Exception {
        User user = new User("delivery-repo-test@mail.com", "hash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        endpoint = new Endpoint("EP 1", "https://example.com/webhook", "whsec_1", app);
        message = new Message(app, event, new ObjectMapper().readTree("{\"amount\":1}"));

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(env);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);
    }

    @Test
    void save_thenFindByMessageIdAndEndpointId_returnsTheSameDelivery() throws Exception {
        arrangeFixtures();
        Delivery delivery = new Delivery(app, message, endpoint);
        testEntityManager.persistAndFlush(delivery);

        Optional<Delivery> found = underTest.findByMessageIdAndEndpointId(message.getId(), endpoint.getId());

        assertTrue(found.isPresent());
        assertEquals(delivery.getId(), found.get().getId());
    }

    @Test
    void save_rejectsASecondDelivery_forTheSameMessageAndEndpointPair() throws Exception {
        arrangeFixtures();
        testEntityManager.persistAndFlush(new Delivery(app, message, endpoint));

        Delivery duplicate = new Delivery(app, message, endpoint);
        assertThrows(DataIntegrityViolationException.class,
                () -> underTest.saveAndFlush(duplicate));
    }

    @Test
    void findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId_returnsEmpty_whenUserDoesNotOwnIt()
            throws Exception {
        arrangeFixtures();
        Delivery delivery = new Delivery(app, message, endpoint);
        testEntityManager.persistAndFlush(delivery);

        Optional<Delivery> found = underTest.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(
                delivery.getId(), app.getId(), app.getEnvironment().getId(), UUID.randomUUID());

        assertTrue(found.isEmpty());
    }
}
