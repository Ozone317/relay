package com.example.relay.deliveryengine.destination;

public class DestinationPolicyBlockedException extends RuntimeException {

    private final String category;

    public DestinationPolicyBlockedException(String category) {
        super("Destination address is blocked by policy: " + category);
        this.category = category;
    }

    public String category() {
        return category;
    }
}
