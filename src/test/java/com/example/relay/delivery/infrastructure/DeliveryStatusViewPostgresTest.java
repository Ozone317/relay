package com.example.relay.delivery.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.relay.app.domain.App;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.message.domain.Message;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DeliveryStatusViewPostgresTest implements SharedPostgresContainer {

    @Autowired
    private TestEntityManager testEntityManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void deliveryStatusView_returnsOnlyTheHighestAttemptNoRow_perDelivery() throws Exception {
        User user = new User("view-test@mail.com", "hash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.completed", app);
        Endpoint endpoint = new Endpoint("EP 1", "https://example.com/webhook", "whsec_1", app);
        Message message = new Message(app, event, new ObjectMapper().readTree("{\"amount\":1}"));

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(env);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(message);

        UUID deliveryId = UUID.randomUUID();
        entityManager.createNativeQuery(
                "INSERT INTO deliveries (id, app_id, message_id, endpoint_id) VALUES (?, ?, ?, ?)")
                .setParameter(1, deliveryId)
                .setParameter(2, app.getId())
                .setParameter(3, message.getId())
                .setParameter(4, endpoint.getId())
                .executeUpdate();

        // Two attempts for the same delivery: an earlier failed one, a later scheduled retry.
        // The view must report the LATER one (attempt_no 2), not the earlier one, even though
        // both rows exist. Uses the CURRENT (pre-Task-4) 4-argument Attempt constructor - Task 4
        // is what adds the delivery field/argument, and updates this file's two calls to the new
        // 5-argument form with a real, persisted Delivery, at the same time it removes the
        // native-SQL delivery_id assignment below in favor of the entity actually carrying it.
        Attempt first = new Attempt(app, message, endpoint, 1);
        testEntityManager.persist(first);
        entityManager.createNativeQuery("UPDATE attempts SET delivery_id = ?, status = 'FAILED_RETRYING' WHERE id = ?")
                .setParameter(1, deliveryId)
                .setParameter(2, first.getId())
                .executeUpdate();

        Attempt second = new Attempt(app, message, endpoint, 2);
        testEntityManager.persist(second);
        entityManager.createNativeQuery("UPDATE attempts SET delivery_id = ?, status = 'SCHEDULED' WHERE id = ?")
                .setParameter(1, deliveryId)
                .setParameter(2, second.getId())
                .executeUpdate();
        testEntityManager.flush();

        Object[] row = (Object[]) entityManager
                .createNativeQuery("SELECT status, attempt_no, attempt_count FROM delivery_status WHERE delivery_id = ?")
                .setParameter(1, deliveryId)
                .getSingleResult();

        assertEquals("SCHEDULED", row[0]);
        assertEquals(2, ((Number) row[1]).intValue());
        assertEquals(2L, ((Number) row[2]).longValue());
    }
}
