package com.example.relay.endpoint.domain;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

public final class WebhookUriParser {

    public ParsedWebhookUri parse(String rawUrl) throws InvalidWebhookUriException {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw invalid("URL must not be blank");
        }
        URI input;
        try {
            input = new URI(rawUrl);
        } catch (URISyntaxException exception) {
            throw new InvalidWebhookUriException("URL has invalid URI syntax", exception);
        }
        if (!input.isAbsolute() || input.getRawAuthority() == null || input.getRawFragment() != null) {
            throw invalid("URL must be an absolute HTTP or HTTPS URI without a fragment");
        }
        String scheme = input.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw invalid("URL must use HTTP or HTTPS");
        }

        String rawAuthority = input.getRawAuthority();
        if (rawAuthority.indexOf('@') >= 0 || rawAuthority.indexOf('%') >= 0 || rawAuthority.isEmpty()) {
            throw invalid("URL authority is invalid");
        }
        boolean bracketed = rawAuthority.charAt(0) == '[';
        boolean explicitPort;
        int port;
        String rawHost;
        if (bracketed) {
            int close = rawAuthority.indexOf(']');
            if (close < 0 || close == 1) {
                throw invalid("IPv6 authority is invalid");
            }
            rawHost = rawAuthority.substring(1, close);
            String suffix = rawAuthority.substring(close + 1);
            if (suffix.isEmpty()) {
                explicitPort = false;
                port = defaultPort(scheme);
            } else {
                if (!suffix.startsWith(":")) {
                    throw invalid("IPv6 authority is invalid");
                }
                explicitPort = true;
                port = parsePort(suffix.substring(1));
            }
        } else {
            int colon = rawAuthority.indexOf(':');
            if (colon < 0) {
                rawHost = rawAuthority;
                explicitPort = false;
                port = defaultPort(scheme);
            } else {
                if (colon != rawAuthority.lastIndexOf(':') || colon == 0) {
                    throw invalid("IPv6 literals must be bracketed");
                }
                rawHost = rawAuthority.substring(0, colon);
                explicitPort = true;
                port = parsePort(rawAuthority.substring(colon + 1));
            }
        }

        Optional<IpLiteral> literal = IpLiteral.parse(rawHost);
        String normalizedHost;
        IpLiteral literalAddress = literal.orElse(null);
        if (literalAddress != null) {
            normalizedHost = IpLiteral.format(literalAddress);
        } else {
            if (bracketed || rawHost.indexOf(':') >= 0 || looksAmbiguousNumeric(rawHost)) {
                throw invalid("Host is not a valid IP literal");
            }
            normalizedHost = normalizeDnsHost(rawHost);
        }

        String authority = bracketed ? "[" + normalizedHost + "]" : normalizedHost;
        if (explicitPort) {
            authority += ":" + port;
        }
        StringBuilder normalized = new StringBuilder(scheme).append("://").append(authority);
        if (input.getRawPath() != null) {
            normalized.append(input.getRawPath());
        }
        if (input.getRawQuery() != null) {
            normalized.append('?').append(input.getRawQuery());
        }
        try {
            return new ParsedWebhookUri(new URI(normalized.toString()), scheme, normalizedHost, port, literalAddress);
        } catch (URISyntaxException exception) {
            throw new InvalidWebhookUriException("URL has invalid normalized syntax", exception);
        }
    }

    private static String normalizeDnsHost(String rawHost) {
        String host = rawHost;
        if (host.endsWith(".")) {
            if (host.length() == 1 || host.endsWith("..")) {
                throw invalid("DNS host has invalid trailing dots");
            }
            host = host.substring(0, host.length() - 1);
        }
        final String ascii;
        try {
            ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw new InvalidWebhookUriException("DNS host is invalid", exception);
        }
        if (ascii.isEmpty() || ascii.length() > 253 || ascii.endsWith(".")) {
            throw invalid("DNS host is invalid");
        }
        String[] labels = ascii.split("\\.", -1);
        if (labels.length < 2) {
            throw invalid("DNS host must contain at least two labels");
        }
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63) {
                throw invalid("DNS host label is invalid");
            }
        }
        return ascii;
    }

    private static boolean looksAmbiguousNumeric(String host) {
        if (host.isEmpty()) {
            return false;
        }
        if (host.matches("(?i)0x[0-9a-f]+(?:\\.[0-9a-f]+)*")) {
            return true;
        }
        return host.chars().allMatch(character -> character >= '0' && character <= '9'
                || character == '.');
    }

    private static int defaultPort(String scheme) {
        return scheme.equals("https") ? 443 : 80;
    }

    private static int parsePort(String rawPort) {
        if (rawPort.isEmpty()) {
            throw invalid("Port is missing");
        }
        for (int i = 0; i < rawPort.length(); i++) {
            if (rawPort.charAt(i) < '0' || rawPort.charAt(i) > '9') {
                throw invalid("Port is invalid");
            }
        }
        try {
            int port = Integer.parseInt(rawPort);
            if (port < 1 || port > 65535) {
                throw invalid("Port is outside the valid range");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new InvalidWebhookUriException("Port is outside the valid range", exception);
        }
    }

    private static InvalidWebhookUriException invalid(String message) {
        return new InvalidWebhookUriException(message);
    }
}
