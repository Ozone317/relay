package com.example.relay.deliveryengine.destination;

import com.example.relay.endpoint.domain.IpLiteral;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pinned, reviewed special-purpose address data used by the destination policy. */
public final class SpecialPurposeAddressCatalog {

    public static final String RESOURCE = "/security/iana-special-purpose-addresses-2025-10-09.txt";

    private final List<IpPrefix> prefixes;

    public SpecialPurposeAddressCatalog() {
        this(loadResource());
    }

    public SpecialPurposeAddressCatalog(InputStream resource) {
        if (resource == null) {
            throw new IllegalArgumentException("Policy resource is missing");
        }
        this.prefixes = List.copyOf(read(resource));
    }

    public List<IpPrefix> prefixes() {
        return prefixes;
    }

    public List<IpPrefix> entries() {
        return prefixes;
    }

    public static IpPrefix parsePrefix(String cidr, String category) {
        if (cidr == null || category == null) {
            throw new IllegalArgumentException("Prefix and category are required");
        }
        int slash = cidr.indexOf('/');
        if (slash <= 0 || slash != cidr.lastIndexOf('/') || slash == cidr.length() - 1) {
            throw new IllegalArgumentException("Invalid CIDR prefix: " + cidr);
        }
        byte[] address = IpLiteral.parse(cidr.substring(0, slash)).orElseThrow(
                () -> new IllegalArgumentException("Invalid CIDR address: " + cidr)).addressBytes();
        int prefixLength;
        try {
            prefixLength = Integer.parseInt(cidr.substring(slash + 1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid CIDR length: " + cidr, exception);
        }
        return new IpPrefix(address, prefixLength, category);
    }

    private static InputStream loadResource() {
        InputStream resource = SpecialPurposeAddressCatalog.class.getResourceAsStream(RESOURCE);
        if (resource == null) {
            throw new IllegalStateException("Missing pinned address-policy resource " + RESOURCE);
        }
        return resource;
    }

    private static List<IpPrefix> read(InputStream resource) {
        List<IpPrefix> result = new ArrayList<>();
        Set<String> normalizedPrefixes = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] fields = trimmed.split("\\|", -1);
                if (fields.length != 2 || fields[0].isBlank() || fields[1].isBlank()) {
                    throw new IllegalStateException("Malformed policy row at line " + lineNumber);
                }
                IpPrefix prefix;
                try {
                    prefix = parsePrefix(fields[0].trim(), fields[1].trim());
                } catch (IllegalArgumentException exception) {
                    throw new IllegalStateException("Malformed policy row at line " + lineNumber, exception);
                }
                String normalized = render(prefix);
                if (!normalizedPrefixes.add(normalized)) {
                    throw new IllegalStateException("Duplicate normalized policy prefix: " + normalized);
                }
                result.add(prefix);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read pinned address-policy resource", exception);
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("Pinned address-policy resource is empty");
        }
        return result;
    }

    private static String render(IpPrefix prefix) {
        byte[] bytes = prefix.addressBytes();
        StringBuilder result = new StringBuilder();
        if (bytes.length == 4) {
            for (int i = 0; i < bytes.length; i++) {
                if (i > 0) {
                    result.append('.');
                }
                result.append(Byte.toUnsignedInt(bytes[i]));
            }
        } else {
            for (int i = 0; i < bytes.length; i += 2) {
                if (i > 0) {
                    result.append(':');
                }
                result.append(String.format("%02x%02x", bytes[i] & 0xff, bytes[i + 1] & 0xff));
            }
        }
        return result.append('/').append(prefix.prefixLength()).toString();
    }
}
