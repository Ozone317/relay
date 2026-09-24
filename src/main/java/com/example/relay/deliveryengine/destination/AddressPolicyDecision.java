package com.example.relay.deliveryengine.destination;

public record AddressPolicyDecision(boolean allowed, String category) {

    public static AddressPolicyDecision allow() {
        return new AddressPolicyDecision(true, null);
    }

    public static AddressPolicyDecision block(String category) {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("Blocked address category must not be blank");
        }
        return new AddressPolicyDecision(false, category);
    }
}
