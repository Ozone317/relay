package com.example.relay.deliveryengine.destination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SpecialPurposeAddressCatalogTest {

    private static final String RESOURCE = "/security/iana-special-purpose-addresses-2025-10-09.txt";

    @Test
    void resourceContainsEveryNormalizedPrefixExactlyOnce() throws IOException {
        Set<String> prefixes = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream(RESOURCE), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\\|", -1);
                assertEquals(2, fields.length, line);
                assertTrue(prefixes.add(fields[0]), "duplicate resource prefix: " + fields[0]);
            }
        }
        assertTrue(prefixes.size() >= 40);
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

    @ParameterizedTest
    @MethodSource("boundaryCases")
    void everyCatalogPrefixMatchesItsFirstAndLastAddressAndNotAdjacentOutside(String cidr, String category) {
        IpPrefix prefix = SpecialPurposeAddressCatalog.parsePrefix(cidr, category);
        byte[] first = prefix.addressBytes();
        byte[] last = lastAddress(first, prefix.prefixLength());
        assertTrue(prefix.contains(first));
        assertTrue(prefix.contains(last));

        byte[] before = adjacent(first, -1);
        if (before != null) {
            assertFalse(prefix.contains(before));
        }
        byte[] after = adjacent(last, 1);
        if (after != null) {
            assertFalse(prefix.contains(after));
        }
    }

    static Stream<Arguments> boundaryCases() {
        return Stream.of(
                Arguments.of("127.0.0.0/8", "LOOPBACK"),
                Arguments.of("10.0.0.0/8", "PRIVATE_USE"),
                Arguments.of("100.64.0.0/10", "SHARED_ADDRESS_SPACE"),
                Arguments.of("169.254.0.0/16", "LINK_LOCAL"),
                Arguments.of("224.0.0.0/4", "MULTICAST"),
                Arguments.of("2001:db8::/32", "DOCUMENTATION"),
                Arguments.of("2001:2::/48", "BENCHMARKING"),
                Arguments.of("fc00::/7", "UNIQUE_LOCAL"),
                Arguments.of("fe80::/10", "LINK_LOCAL"));
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
