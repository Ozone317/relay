package com.example.relay.attempt.infrastructure;

import com.example.relay.attempt.domain.Attempt;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptRepository extends JpaRepository<Attempt, UUID> {

}
