package com.example.relay.subscription.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.example.relay.subscription.api.dto.SubscriptionResponseDto;
import com.example.relay.subscription.domain.Subscription;

@Component
public class SubscriptionMapper {

    public SubscriptionResponseDto toResponseDto(Subscription subscription) {
        return new SubscriptionResponseDto(
            subscription.getId(),
            subscription.getApp().getId(),
            subscription.getEvent().getId(),
            subscription.getEvent().getName(),
            subscription.getEndpoint().getId(),
            subscription.getCreatedAt()
        );
    }

    public List<SubscriptionResponseDto> toResponseDtoList(List<Subscription> subscriptions) {
        return subscriptions.stream()
            .map(this::toResponseDto)
            .toList();
    }
}
