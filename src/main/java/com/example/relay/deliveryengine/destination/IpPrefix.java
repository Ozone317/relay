package com.example.relay.deliveryengine.destination;

import java.util.Arrays;
import java.util.Objects;

/** An immutable raw-byte network prefix. */
public final class IpPrefix {

    private final byte[] addressBytes;
    private final int prefixLength;
    private final String category;

    public IpPrefix(byte[] addressBytes, int prefixLength, String category) {
        Objects.requireNonNull(addressBytes, "addressBytes");
        if (addressBytes.length != 4 && addressBytes.length != 16) {
            throw new IllegalArgumentException("IP prefixes must contain four or sixteen bytes");
        }
        if (prefixLength < 0 || prefixLength > addressBytes.length * 8) {
            throw new IllegalArgumentException("Prefix length is outside the address width");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("Prefix category must not be blank");
        }
        this.addressBytes = addressBytes.clone();
        clearHostBits(this.addressBytes, prefixLength);
        this.prefixLength = prefixLength;
        this.category = category;
    }

    public byte[] addressBytes() {
        return addressBytes.clone();
    }

    public byte[] networkBytes() {
        return addressBytes();
    }

    public int prefixLength() {
        return prefixLength;
    }

    public String category() {
        return category;
    }

    public boolean contains(byte[] candidate) {
        if (candidate == null || candidate.length != addressBytes.length) {
            return false;
        }
        int completeBytes = prefixLength / 8;
        if (!Arrays.equals(Arrays.copyOf(candidate, completeBytes), Arrays.copyOf(addressBytes, completeBytes))) {
            return false;
        }
        int remainingBits = prefixLength % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xff << (8 - remainingBits);
        return (candidate[completeBytes] & mask) == (addressBytes[completeBytes] & mask);
    }

    private static void clearHostBits(byte[] bytes, int prefixLength) {
        int completeBytes = prefixLength / 8;
        int remainingBits = prefixLength % 8;
        if (remainingBits != 0) {
            bytes[completeBytes] &= (byte) (0xff << (8 - remainingBits));
            completeBytes++;
        }
        Arrays.fill(bytes, completeBytes, bytes.length, (byte) 0);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof IpPrefix that)) {
            return false;
        }
        return prefixLength == that.prefixLength && Arrays.equals(addressBytes, that.addressBytes)
                && category.equals(that.category);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(addressBytes), prefixLength, category);
    }
}
