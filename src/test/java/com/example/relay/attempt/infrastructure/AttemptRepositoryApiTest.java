package com.example.relay.attempt.infrastructure;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class AttemptRepositoryApiTest {

    @Test
    void doesNotExposeAnExecutionClaimMutation() {
        assertFalse(java.util.Arrays.stream(AttemptRepository.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("claim")));
    }
}
