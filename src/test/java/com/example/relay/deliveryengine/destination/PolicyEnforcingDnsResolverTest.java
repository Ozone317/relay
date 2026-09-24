package com.example.relay.deliveryengine.destination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.example.relay.deliveryengine.http.DeliveryDeadline;
import com.example.relay.deliveryengine.http.DeliveryDeadlineContext;

class PolicyEnforcingDnsResolverTest {

    private static final int PORT = 8443;

    @Test
    void protectedOnlyResultThrowsPolicyBlocked() {
        PolicyEnforcingDnsResolver resolver = resolverWith(List.of(address("127.0.0.1")));
        assertThrows(DestinationPolicyBlockedException.class, () -> resolve(resolver, "hooks.example.test"));
    }

    @Test
    void mixedPublicAndProtectedResultRejectsTheWholeSet() {
        PolicyEnforcingDnsResolver resolver = resolverWith(
                List.of(address("93.184.216.34"), address("10.0.0.1")));
        assertThrows(DestinationPolicyBlockedException.class, () -> resolve(resolver, "hooks.example.test"));
    }

    @Test
    void onePermittedResultReturnsOneResolvedSocketAddress() throws Exception {
        PolicyEnforcingDnsResolver resolver = resolverWith(List.of(address("93.184.216.34")));
        List<InetSocketAddress> result = resolve(resolver, "hooks.example.test");
        assertEquals(1, result.size());
        assertEquals(PORT, result.get(0).getPort());
        assertFalse(result.get(0).isUnresolved());
        assertEquals("93.184.216.34", result.get(0).getAddress().getHostAddress());
    }

    @Test
    void multiplePermittedResultsPreserveOnlyTheValidatedSetAndOrder() throws Exception {
        PolicyEnforcingDnsResolver resolver = resolverWith(
                List.of(address("93.184.216.34"), address("93.184.216.35")));
        List<InetSocketAddress> result = resolve(resolver, "hooks.example.test");
        assertEquals(List.of("93.184.216.34", "93.184.216.35"),
                result.stream().map(value -> value.getAddress().getHostAddress()).toList());
    }

    @Test
    void nullLookupResultFailsClosed() {
        PolicyEnforcingDnsResolver resolver = resolverWith(null);
        assertThrows(UnknownHostException.class, () -> resolve(resolver, "hooks.example.test"));
    }

    @Test
    void emptyLookupResultFailsClosed() {
        PolicyEnforcingDnsResolver resolver = resolverWith(List.of());
        assertThrows(UnknownHostException.class, () -> resolve(resolver, "hooks.example.test"));
    }

    @Test
    void unresolvedSocketAddressCanNeverBeReturned() throws Exception {
        AtomicReference<String> lookedUp = new AtomicReference<>();
        HostAddressLookup lookup = (hostname, deadline) -> {
            lookedUp.set(hostname);
            return List.of(address("93.184.216.34"));
        };
        PolicyEnforcingDnsResolver resolver = new PolicyEnforcingDnsResolver(lookup,
                new PublicDestinationAddressPolicy());
        List<InetSocketAddress> result = resolve(resolver, "hooks.example.test");
        assertFalse(result.stream().anyMatch(InetSocketAddress::isUnresolved));
        assertEquals("hooks.example.test.", lookedUp.get());
    }

    @Test
    void hostnameLookupReceivesExactlyOneTerminatingDot() throws Exception {
        AtomicReference<String> lookedUp = new AtomicReference<>();
        HostAddressLookup lookup = (hostname, deadline) -> {
            lookedUp.set(hostname);
            return List.of(address("93.184.216.34"));
        };
        PolicyEnforcingDnsResolver resolver = new PolicyEnforcingDnsResolver(lookup,
                new PublicDestinationAddressPolicy());
        resolve(resolver, "hooks.example.test");
        assertEquals("hooks.example.test.", lookedUp.get());
    }

    @Test
    void literalDoesNotEnterSystemHostnameLookup() throws Exception {
        AtomicReference<String> lookedUp = new AtomicReference<>();
        HostAddressLookup lookup = (hostname, deadline) -> {
            lookedUp.set(hostname);
            throw new AssertionError("literal entered hostname lookup");
        };
        PolicyEnforcingDnsResolver resolver = new PolicyEnforcingDnsResolver(lookup,
                new PublicDestinationAddressPolicy());
        List<InetSocketAddress> result = resolve(resolver, "93.184.216.34");
        assertEquals(1, result.size());
        assertEquals(null, lookedUp.get());
    }

    @Test
    void permittedLiteralRequiresDeliveryDeadlineContext() {
        PolicyEnforcingDnsResolver resolver = resolverWith(List.of());
        assertThrows(IllegalStateException.class, () -> resolver.resolve("93.184.216.34", PORT));
    }

    @Test
    void expiredDeadlineRejectsPermittedLiteralBeforeReturningSocket() {
        PolicyEnforcingDnsResolver resolver = resolverWith(List.of());
        DeliveryDeadline deadline = DeliveryDeadline.start(Duration.ZERO, () -> 0L);
        assertThrows(UnknownHostException.class, () -> {
            try (DeliveryDeadlineContext.Scope ignored = DeliveryDeadlineContext.open(deadline)) {
                resolver.resolve("93.184.216.34", PORT);
            }
        });
    }

    @Test
    void nonCanonicalHostnameIsRejectedBeforeLookup() {
        AtomicReference<String> lookedUp = new AtomicReference<>();
        HostAddressLookup lookup = (hostname, deadline) -> {
            lookedUp.set(hostname);
            return List.of(address("93.184.216.34"));
        };
        PolicyEnforcingDnsResolver resolver = new PolicyEnforcingDnsResolver(lookup,
                new PublicDestinationAddressPolicy());
        assertThrows(UnknownHostException.class, () -> resolve(resolver, "Hooks.Example.Test"));
        assertEquals(null, lookedUp.get());
        assertThrows(UnknownHostException.class, () -> resolve(resolver, "hooks.example.test."));
        assertEquals(null, lookedUp.get());
    }

    @Test
    void deadlineExpiryBeforeLookupFailsWithoutCallingLookup() {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        HostAddressLookup lookup = (hostname, deadline) -> {
            called.set(true);
            return List.of(address("93.184.216.34"));
        };
        PolicyEnforcingDnsResolver resolver = new PolicyEnforcingDnsResolver(lookup,
                new PublicDestinationAddressPolicy());
        AtomicLong now = new AtomicLong();
        DeliveryDeadline deadline = DeliveryDeadline.start(Duration.ofNanos(1), now::getAndIncrement);
        assertThrows(UnknownHostException.class, () -> {
            try (DeliveryDeadlineContext.Scope ignored = DeliveryDeadlineContext.open(deadline)) {
                resolver.resolve("hooks.example.test", PORT);
            }
        });
        assertEquals(false, called.get());
    }

    private PolicyEnforcingDnsResolver resolverWith(List<InetAddress> addresses) {
        return new PolicyEnforcingDnsResolver((hostname, deadline) -> addresses,
                new PublicDestinationAddressPolicy());
    }

    private List<InetSocketAddress> resolve(PolicyEnforcingDnsResolver resolver, String host)
            throws UnknownHostException {
        try (DeliveryDeadlineContext.Scope ignored = DeliveryDeadlineContext.open(
                DeliveryDeadline.start(Duration.ofSeconds(5), System::nanoTime))) {
            return resolver.resolve(host, PORT);
        }
    }

    private static InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException exception) {
            throw new AssertionError(exception);
        }
    }
}
