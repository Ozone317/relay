package com.example.relay.deliveryengine.destination;

public class DnsResolutionException extends Exception {

    public DnsResolutionException(String message) {
        super(message);
    }

    public DnsResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
