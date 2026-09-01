package com.example.relay.attempt.infrastructure;

import com.example.relay.attempt.domain.Attempt;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AttemptRepository extends JpaRepository<Attempt, UUID> {

    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE attempts
                SET status = 'IN_FLIGHT'
                WHERE id = :attemptId
                AND status = 'CREATED'
            """, nativeQuery = true)
    int claim(UUID attemptId);
}
