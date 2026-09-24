package com.example.relay.deliveryengine.destination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpecialPurposeAddressCatalogTest {

    private static final String RESOURCE = "/security/iana-special-purpose-addresses-2025-10-09.txt";

    private static final List<String> EXPECTED_ROWS = List.of(
            "0.0.0.0/8|THIS_NETWORK",
            "0.0.0.0/32|THIS_HOST",
            "10.0.0.0/8|PRIVATE_USE",
            "100.64.0.0/10|SHARED_ADDRESS_SPACE",
            "127.0.0.0/8|LOOPBACK",
            "169.254.0.0/16|LINK_LOCAL",
            "172.16.0.0/12|PRIVATE_USE",
            "192.0.0.0/24|IETF_PROTOCOL_ASSIGNMENTS",
            "192.0.0.0/29|SERVICE_CONTINUITY",
            "192.0.0.8/32|DUMMY_ADDRESS",
            "192.0.0.9/32|PORT_CONTROL_ANYCAST",
            "192.0.0.10/32|NAT_TRAVERSAL_ANYCAST",
            "192.0.0.170/32|NAT64_DISCOVERY",
            "192.0.0.171/32|NAT64_DISCOVERY",
            "192.0.2.0/24|DOCUMENTATION",
            "192.31.196.0/24|AS112",
            "192.52.193.0/24|AMT",
            "192.88.99.0/24|SIX_TO_FOUR_RELAY_DEPRECATED",
            "192.88.99.2/32|SIX_TO_FOUR_RELAY_ANYCAST",
            "192.168.0.0/16|PRIVATE_USE",
            "192.175.48.0/24|AS112",
            "198.18.0.0/15|BENCHMARKING",
            "198.51.100.0/24|DOCUMENTATION",
            "203.0.113.0/24|DOCUMENTATION",
            "224.0.0.0/4|MULTICAST",
            "240.0.0.0/4|RESERVED",
            "255.255.255.255/32|LIMITED_BROADCAST",
            "::1/128|LOOPBACK",
            "::/128|UNSPECIFIED",
            "::/96|IPV4_COMPATIBLE_DEPRECATED",
            "::ffff:0:0/96|IPV4_MAPPED",
            "64:ff9b::/96|NAT64",
            "64:ff9b:1::/48|NAT64",
            "100::/64|DISCARD_ONLY",
            "100:0:0:1::/64|DUMMY_PREFIX",
            "2001::/23|IETF_PROTOCOL_ASSIGNMENTS",
            "2001::/32|TEREDO",
            "2001:1::1/128|PORT_CONTROL_ANYCAST",
            "2001:1::2/128|NAT_TRAVERSAL_ANYCAST",
            "2001:1::3/128|DNS_SD_ANYCAST",
            "2001:2::/48|BENCHMARKING",
            "2001:3::/32|AMT",
            "2001:4:112::/48|AS112",
            "2001:10::/28|ORCHID_DEPRECATED",
            "2001:20::/28|ORCHID",
            "2001:30::/28|DRONE_REMOTE_ID",
            "2001:db8::/32|DOCUMENTATION",
            "2002::/16|SIX_TO_FOUR",
            "2620:4f:8000::/48|AS112",
            "3fff::/20|DOCUMENTATION",
            "5f00::/16|SRV6_SID",
            "fc00::/7|UNIQUE_LOCAL",
            "fe80::/10|LINK_LOCAL",
            "fec0::/10|SITE_LOCAL_DEPRECATED",
            "ff00::/8|MULTICAST");

    @Test
    void resourceMatchesTheReviewedNormalizedTableExactly() throws IOException {
        assertEquals(EXPECTED_ROWS, resourceRows());
    }

    @Test
    void resourceRecordsApprovedProvenanceMetadata() throws IOException {
        String metadata;
        try (InputStream stream = resource()) {
            metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String line : List.of(
                "# Relay policy format version: 1",
                "# Source IPv4: https://www.iana.org/assignments/iana-ipv4-special-registry/iana-ipv4-special-registry-1.csv",
                "# Source IPv6: https://www.iana.org/assignments/iana-ipv6-special-registry/iana-ipv6-special-registry-1.csv",
                "# IANA registry last-updated: 2025-10-09",
                "# Retrieval date: 2026-09-24",
                "# Source IPv4 CSV SHA-256: e3e39e76d00b1677335db8e9a805c7b9480ea2f4dc9e33f0b93cd3a905128d73",
                "# Source IPv6 CSV SHA-256: 775feea0621dec8735a44fbf30f762e721e8f0a1b3ab7eb341961a88cfce2139")) {
            assertTrue(metadata.contains(line), "missing metadata: " + line);
        }
    }

    @Test
    void catalogLoadsImmutableValidatedEntries() {
        SpecialPurposeAddressCatalog catalog = new SpecialPurposeAddressCatalog();

        assertNotNull(catalog.prefixes());
        assertFalse(catalog.prefixes().isEmpty());
        assertThrowsUnsupported(catalog.prefixes());
        catalog.prefixes().forEach(prefix -> {
            int width = prefix.addressBytes().length;
            assertTrue(width == 4 || width == 16);
            assertTrue(prefix.prefixLength() >= 0 && prefix.prefixLength() <= width * 8);
            assertFalse(prefix.category().isBlank());
        });
    }

    @Test
    void catalogRejectsDuplicateNormalizedPrefixes() {
        String rows = "# test\n10.0.0.1/8|FIRST\n10.0.0.0/8|SECOND\n";

        assertThrows(IllegalStateException.class, () -> new SpecialPurposeAddressCatalog(
                new ByteArrayInputStream(rows.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void everyReviewedPrefixBlocksBothEndpointsAndHandlesAdjacentOverlaps() {
        SpecialPurposeAddressCatalog catalog = new SpecialPurposeAddressCatalog();
        PublicDestinationAddressPolicy policy = new PublicDestinationAddressPolicy(catalog);
        List<IpPrefix> reviewedPrefixes = reviewedPrefixes();
        assertEquals(EXPECTED_ROWS.size(), catalog.prefixes().size());

        for (int index = 0; index < EXPECTED_ROWS.size(); index++) {
            String row = EXPECTED_ROWS.get(index);
            String[] fields = row.split("\\|", -1);
            IpPrefix prefix = reviewedPrefixes.get(index);
            IpPrefix loadedPrefix = catalog.prefixes().get(index);
            assertArrayEquals(prefix.addressBytes(), loadedPrefix.addressBytes(), row);
            assertEquals(prefix.prefixLength(), loadedPrefix.prefixLength(), row);
            assertEquals(prefix.category(), loadedPrefix.category(), row);
            byte[] first = prefix.addressBytes();
            byte[] last = lastAddress(first, prefix.prefixLength());
            assertTrue(prefix.contains(first), row);
            assertTrue(prefix.contains(last), row);
            assertFalse(policy.evaluate(first).allowed(), "first endpoint was allowed: " + row);
            assertFalse(policy.evaluate(last).allowed(), "last endpoint was allowed: " + row);
            assertEquals(fields[1], prefix.category(), row);

            assertAdjacentExpectation(reviewedPrefixes, policy, first, -1, row + " before");
            assertAdjacentExpectation(reviewedPrefixes, policy, last, 1, row + " after");
        }
    }

    private static void assertAdjacentExpectation(List<IpPrefix> prefixes, PublicDestinationAddressPolicy policy,
            byte[] boundary, int delta, String description) {
        byte[] adjacent = adjacent(boundary, delta);
        if (adjacent == null) {
            return;
        }
        boolean coveredByReviewedPrefix = prefixes.stream().anyMatch(prefix -> prefix.contains(adjacent));
        assertEquals(coveredByReviewedPrefix, !policy.evaluate(adjacent).allowed(), description);
    }

    private static List<IpPrefix> reviewedPrefixes() {
        return EXPECTED_ROWS.stream().map(row -> {
            String[] fields = row.split("\\|", -1);
            return SpecialPurposeAddressCatalog.parsePrefix(fields[0], fields[1]);
        }).toList();
    }

    private static List<String> resourceRows() throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource(), StandardCharsets.UTF_8))) {
            return reader.lines().map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        }
    }

    private static InputStream resource() {
        InputStream stream = SpecialPurposeAddressCatalogTest.class.getResourceAsStream(RESOURCE);
        assertNotNull(stream, "missing resource " + RESOURCE);
        return stream;
    }

    private static byte[] lastAddress(byte[] first, int prefixLength) {
        byte[] last = first.clone();
        for (int bit = prefixLength; bit < last.length * 8; bit++) {
            last[bit / 8] |= (byte) (1 << (7 - (bit % 8)));
        }
        return last;
    }

    private static byte[] adjacent(byte[] value, int delta) {
        byte[] result = value.clone();
        if (delta < 0) {
            for (int i = result.length - 1; i >= 0; i--) {
                int unsigned = Byte.toUnsignedInt(result[i]);
                if (unsigned > 0) {
                    result[i] = (byte) (unsigned - 1);
                    return result;
                }
                result[i] = (byte) 0xff;
            }
        } else {
            for (int i = result.length - 1; i >= 0; i--) {
                int unsigned = Byte.toUnsignedInt(result[i]);
                if (unsigned < 255) {
                    result[i] = (byte) (unsigned + 1);
                    return result;
                }
                result[i] = 0;
            }
        }
        return null;
    }

    private static void assertThrowsUnsupported(List<?> values) {
        try {
            values.clear();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("catalog list must be immutable");
    }
}
