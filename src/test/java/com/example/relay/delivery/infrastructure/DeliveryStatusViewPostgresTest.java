package com.example.relay.delivery.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.relay.app.domain.App;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.message.domain.Message;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

        Delivery delivery = new Delivery(app, message, endpoint);
        testEntityManager.persistAndFlush(delivery);

        // Two attempts for the same delivery: an earlier failed one, a later scheduled retry.
        // The view must report the one with the HIGHER attempt_no (2), not the earlier one, even
        // though both rows exist. The second attempt is deliberately backdated below to BEFORE the
        // first attempt's created_at, so attempt_no ordering and created_at ordering disagree - this
        // is the only way to actually pin down that the view orders by attempt_no DESC (the correct,
        // deliberate design - this project's own clock-skew lesson) rather than created_at DESC (the
        // wrong ordering that was deliberately rejected). With both attempts persisted in ascending
        // order and no backdating, attempt_no and created_at naturally agree, and the test would pass
        // identically under either ordering - which was the bug in this test before this fix.
        Attempt first = new Attempt(app, message, endpoint, delivery, 1);
        testEntityManager.persist(first);
        entityManager.createNativeQuery("UPDATE attempts SET status = 'FAILED_RETRYING' WHERE id = ?")
                .setParameter(1, first.getId())
                .executeUpdate();

        Attempt second = new Attempt(app, message, endpoint, delivery, 2);
        testEntityManager.persist(second);
        // Backdate using a SQL-side subquery against the first attempt's own created_at, rather than
        // reading Attempt#getCreatedAt() back in Java - @CreationTimestamp only actually populates
        // that field once Hibernate flushes the insert, and relying on the in-memory value here would
        // be fragile to exactly when that flush happens to occur.
        entityManager.createNativeQuery("""
                UPDATE attempts SET status = 'SCHEDULED',
                    created_at = (SELECT created_at FROM attempts WHERE id = :firstId) - INTERVAL '60 seconds'
                WHERE id = :secondId
                """)
                .setParameter("firstId", first.getId())
                .setParameter("secondId", second.getId())
                .executeUpdate();
        testEntityManager.flush();

        Object[] row = (Object[]) entityManager
                .createNativeQuery("SELECT status, attempt_no, attempt_count FROM delivery_status WHERE delivery_id = ?")
                .setParameter(1, delivery.getId())
                .getSingleResult();

        assertEquals("SCHEDULED", row[0]);
        assertEquals(2, ((Number) row[1]).intValue());
        assertEquals(2L, ((Number) row[2]).longValue());
    }
}
