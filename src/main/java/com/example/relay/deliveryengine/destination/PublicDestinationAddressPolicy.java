package com.example.relay.deliveryengine.destination;

import java.util.Objects;

/** Fail-closed policy for ordinary publicly reachable webhook destinations. */
public final class PublicDestinationAddressPolicy {

    private static final SpecialPurposeAddressCatalog DEFAULT_CATALOG = new SpecialPurposeAddressCatalog();

    private final SpecialPurposeAddressCatalog catalog;

    public PublicDestinationAddressPolicy() {
        this(DEFAULT_CATALOG);
    }

    public PublicDestinationAddressPolicy(SpecialPurposeAddressCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public AddressPolicyDecision evaluate(byte[] addressBytes) {
        if (addressBytes == null || (addressBytes.length != 4 && addressBytes.length != 16)) {
            return AddressPolicyDecision.block("INVALID_ADDRESS");
        }
        IpPrefix match = null;
        for (IpPrefix prefix : catalog.prefixes()) {
            if (prefix.contains(addressBytes)
                    && (match == null || prefix.prefixLength() > match.prefixLength())) {
                match = prefix;
            }
        }
        return match == null ? AddressPolicyDecision.allow() : AddressPolicyDecision.block(match.category());
    }

    public void requireAllowed(byte[] addressBytes) throws DestinationPolicyBlockedException {
        AddressPolicyDecision decision = evaluate(addressBytes);
        if (!decision.allowed()) {
            throw new DestinationPolicyBlockedException(decision.category());
        }
    }
}
