package com.example.relay.attempt.application;

import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.message.domain.Message;
import com.example.relay.subscription.domain.Subscription;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttemptService {

    private final AttemptRepository attemptRepository;

    public AttemptService(AttemptRepository attemptRepository) {
        this.attemptRepository = attemptRepository;
    }

    @Transactional
    public List<Attempt> createFromSubscriptionList(List<Subscription> susbcriptions, Message message) {
        List<Attempt> attempts = createAttempts(susbcriptions, message);
        List<Attempt> createdAttempts = attemptRepository.saveAll(attempts);
        return createdAttempts;
    }

    private List<Attempt> createAttempts(List<Subscription> subscriptions, Message message) {
        List<Attempt> attempts = new ArrayList<>();
        for (Subscription sub : subscriptions) {
            Attempt attempt = new Attempt(sub.getApp(), message, sub.getEndpoint(), 1);
            attempts.add(attempt);
        }

        return attempts;
    }
}
