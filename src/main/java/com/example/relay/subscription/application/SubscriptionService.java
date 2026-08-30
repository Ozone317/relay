package com.example.relay.subscription.application;

import com.example.relay.app.application.AppService;
import com.example.relay.app.domain.App;
import com.example.relay.endpoint.application.EndpointService;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.event.application.EventService;
import com.example.relay.event.domain.Event;
import com.example.relay.subscription.domain.Subscription;
import com.example.relay.subscription.infrastructure.SubscriptionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final AppService appService;
    private final EventService eventService;
    private final EndpointService endpointService;

    public SubscriptionService(SubscriptionRepository subscriptionRepository, AppService appService,
            EventService eventService, EndpointService endpointService) {
        this.subscriptionRepository = subscriptionRepository;
        this.appService = appService;
        this.eventService = eventService;
        this.endpointService = endpointService;
    }

    public Subscription create(UUID environmentId, UUID appId, UUID endpointId, UUID eventId, UUID userId) {
        Optional<Subscription> subscription = getSubscription(environmentId, appId, endpointId, eventId, userId);

        if (subscription.isPresent()) {
            return subscription.get();
        }

        App app = appService.getById(appId, environmentId, userId);
        Event event = eventService.getById(eventId, appId, environmentId, userId);
        Endpoint endpoint = endpointService.getById(endpointId, appId, environmentId, userId);

        Subscription created = subscriptionRepository.save(new Subscription(app, event, endpoint));

        return created;
    }

    public List<Subscription> getAll(UUID environmentId, UUID appId, UUID endpointId, UUID userId) {
        List<Subscription> subscriptions = subscriptionRepository
                .findAllByAppIdAndEnvironmentIdAndEndpointIdAndUserId(appId, environmentId, endpointId, userId);

        return subscriptions;
    }

    public void delete(UUID environmentId, UUID appId, UUID endpointId, UUID eventId, UUID userId) {
        Optional<Subscription> subscription = getSubscription(environmentId, appId, endpointId, eventId, userId);

        if (subscription.isEmpty()) {
            return;
        }

        subscriptionRepository.delete(subscription.get());
    }

    private Optional<Subscription> getSubscription(UUID environmentId, UUID appId, UUID endpointId, UUID eventId,
            UUID userId) {
        return subscriptionRepository.findByAppIdAndEnvironmentIdAndEventIdAndEndpointIdAndUserId(appId, environmentId,
                eventId, endpointId, userId);
    }
}
