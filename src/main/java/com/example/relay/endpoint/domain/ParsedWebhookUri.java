package com.example.relay.endpoint.domain;

import java.net.URI;

public record ParsedWebhookUri(URI normalizedUri, String scheme, String normalizedHost, int effectivePort,
        IpLiteral literalAddress) {

    public boolean isLiteral() {
        return literalAddress != null;
    }
}
