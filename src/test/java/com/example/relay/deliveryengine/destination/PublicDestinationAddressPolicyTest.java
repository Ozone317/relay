package com.example.relay.deliveryengine.destination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.endpoint.domain.IpLiteral;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PublicDestinationAddressPolicyTest {

    private final PublicDestinationAddressPolicy policy = new PublicDestinationAddressPolicy();

    @ParameterizedTest
    @MethodSource("blockedLiterals")
    void evaluate_blocksEverySpecialPurposeAddress(String literal, String category) {
        AddressPolicyDecision decision = policy.evaluate(bytes(literal));

        assertFalse(decision.allowed(), literal);
        assertEquals(category, decision.category(), literal);
        assertThrows(DestinationPolicyBlockedException.class, () -> policy.requireAllowed(bytes(literal)));
    }

    static Stream<Arguments> blockedLiterals() {
        return Stream.of(
                Arguments.of("0.0.0.0", "THIS_HOST"),
                Arguments.of("127.0.0.1", "LOOPBACK"),
                Arguments.of("10.1.2.3", "PRIVATE_USE"),
                Arguments.of("100.64.1.2", "SHARED_ADDRESS_SPACE"),
                Arguments.of("169.254.1.2", "LINK_LOCAL"),
                Arguments.of("192.0.2.10", "DOCUMENTATION"),
                Arguments.of("198.18.1.2", "BENCHMARKING"),
                Arguments.of("224.1.2.3", "MULTICAST"),
                Arguments.of("255.255.255.255", "LIMITED_BROADCAST"),
                Arguments.of("::", "UNSPECIFIED"),
                Arguments.of("::1", "LOOPBACK"),
                Arguments.of("::ffff:127.0.0.1", "IPV4_MAPPED"),
                Arguments.of("::ffff:8.8.8.8", "IPV4_MAPPED"),
                Arguments.of("64:ff9b::c000:0201", "NAT64"),
                Arguments.of("2002:c000:0201::", "SIX_TO_FOUR"),
                Arguments.of("2001:0000:4136:e378::1", "TEREDO"),
                Arguments.of("ff02::1", "MULTICAST"),
                Arguments.of("fc00::1", "UNIQUE_LOCAL"),
                Arguments.of("fe80::1", "LINK_LOCAL"));
    }

    @ParameterizedTest
    @MethodSource("ordinaryPublicLiterals")
    void evaluate_allowsRepresentativeOrdinaryPublicAddresses(String literal) {
        AddressPolicyDecision decision = policy.evaluate(bytes(literal));

        assertTrue(decision.allowed(), literal);
    }

    static Stream<String> ordinaryPublicLiterals() {
        return Stream.of("8.8.8.8", "1.1.1.1", "93.184.216.34", "2001:4860:4860::8888",
                "2606:4700:4700::1111");
    }

    @Test
    void evaluate_failsClosedForNullAndInvalidWidths() {
        assertFalse(policy.evaluate(null).allowed());
        assertFalse(policy.evaluate(new byte[3]).allowed());
        assertFalse(policy.evaluate(new byte[5]).allowed());
        assertFalse(policy.evaluate(new byte[15]).allowed());
        assertFalse(policy.evaluate(new byte[17]).allowed());
    }

    private static byte[] bytes(String literal) {
        return IpLiteral.parse(literal).orElseThrow().addressBytes();
    }
}
