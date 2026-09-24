package com.example.relay.deliveryengine.destination;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.hc.client5.http.DnsResolver;

import com.example.relay.deliveryengine.http.DeliveryDeadline;
import com.example.relay.deliveryengine.http.DeliveryDeadlineContext;
import com.example.relay.endpoint.domain.InvalidWebhookUriException;
import com.example.relay.endpoint.domain.IpLiteral;
import com.example.relay.endpoint.domain.WebhookUriParser;

public final class PolicyEnforcingDnsResolver implements DnsResolver {

    private final HostAddressLookup lookup;
    private final PublicDestinationAddressPolicy addressPolicy;

    public PolicyEnforcingDnsResolver(HostAddressLookup lookup, PublicDestinationAddressPolicy addressPolicy) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.addressPolicy = Objects.requireNonNull(addressPolicy, "addressPolicy");
    }

    @Override
    public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
        DeliveryDeadline deadline = DeliveryDeadlineContext.current();
        if (host == null || host.isBlank() || port < 1 || port > 65_535) {
            throw unknownHost(host, new IllegalArgumentException("invalid host or port"));
        }
        var literal = IpLiteral.parse(host);
        if (literal.isPresent()) {
            return List.of(validatedAddress(literal.get().addressBytes(), port));
        }

        if (deadline.remaining().isZero() || deadline.remaining().isNegative()) {
            throw unknownHost(host, new IllegalStateException("delivery deadline expired"));
        }
        String normalizedHost;
        try {
            normalizedHost = WebhookUriParser.normalizeDnsHostname(host);
        } catch (InvalidWebhookUriException exception) {
            throw unknownHost(host, exception);
        }
        if (!normalizedHost.equals(host)) {
            throw unknownHost(host, new IllegalArgumentException("hostname is not canonical"));
        }
        String absoluteHostname = normalizedHost + ".";
        List<InetAddress> candidates;
        try {
            candidates = lookup.lookup(absoluteHostname, deadline);
        } catch (DnsResolutionException exception) {
            throw unknownHost(host, exception);
        }
        if (candidates == null || candidates.isEmpty()) {
            throw unknownHost(host, new IllegalStateException("DNS lookup returned no addresses"));
        }

        List<byte[]> validatedBytes = new ArrayList<>(candidates.size());
        DestinationPolicyBlockedException blocked = null;
        for (InetAddress candidate : candidates) {
            if (candidate == null || candidate.getAddress() == null) {
                throw unknownHost(host, new IllegalStateException("DNS lookup returned an unresolved address"));
            }
            AddressPolicyDecision decision = addressPolicy.evaluate(candidate.getAddress());
            if (decision.allowed()) {
                validatedBytes.add(candidate.getAddress());
            } else if (blocked == null) {
                blocked = new DestinationPolicyBlockedException(decision.category());
            }
        }
        if (blocked != null) {
            throw blocked;
        }
        List<InetSocketAddress> result = new ArrayList<>(validatedBytes.size());
        for (byte[] bytes : validatedBytes) {
            result.add(validatedAddress(bytes, port));
        }
        return List.copyOf(result);
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        throw new IllegalStateException("The port-aware DNS resolver path is required");
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return Objects.requireNonNull(host, "host");
    }

    private InetSocketAddress validatedAddress(byte[] bytes, int port) throws UnknownHostException {
        addressPolicy.requireAllowed(bytes);
        try {
            InetAddress address = InetAddress.getByAddress(bytes);
            InetSocketAddress socketAddress = new InetSocketAddress(address, port);
            if (socketAddress.isUnresolved() || socketAddress.getPort() != port) {
                throw new UnknownHostException("Address could not be resolved");
            }
            return socketAddress;
        } catch (UnknownHostException exception) {
            throw exception;
        }
    }

    private UnknownHostException unknownHost(String host, Throwable cause) {
        UnknownHostException exception = new UnknownHostException("DNS resolution failed for " + host);
        exception.initCause(cause);
        return exception;
    }
}
