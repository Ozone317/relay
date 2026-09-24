package com.example.relay.endpoint.domain;

import java.util.Optional;

public record IpLiteral(byte[] addressBytes) {

    public IpLiteral {
        if (addressBytes == null || (addressBytes.length != 4 && addressBytes.length != 16)) {
            throw new IllegalArgumentException("An IP literal must contain four or sixteen bytes");
        }
        addressBytes = addressBytes.clone();
    }

    @Override
    public byte[] addressBytes() {
        return addressBytes.clone();
    }

    /** Parses only textual IP literals; an empty result denotes a DNS name. */
    public static Optional<IpLiteral> parse(String value) {
        if (value == null || value.isEmpty() || value.indexOf('%') >= 0 || value.indexOf('[') >= 0
                || value.indexOf(']') >= 0) {
            return Optional.empty();
        }
        byte[] ipv4 = parseIpv4(value);
        if (ipv4 != null) {
            return Optional.of(new IpLiteral(ipv4));
        }
        if (value.indexOf(':') < 0) {
            return Optional.empty();
        }
        byte[] ipv6 = parseIpv6(value);
        return ipv6 == null ? Optional.empty() : Optional.of(new IpLiteral(ipv6));
    }

    static byte[] parseIpv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        byte[] result = new byte[4];
        for (int i = 0; i < octets.length; i++) {
            String octet = octets[i];
            if (octet.isEmpty() || (octet.length() > 1 && octet.charAt(0) == '0')) {
                return null;
            }
            int valueOfOctet = 0;
            for (int j = 0; j < octet.length(); j++) {
                char digit = octet.charAt(j);
                if (digit < '0' || digit > '9') {
                    return null;
                }
                valueOfOctet = valueOfOctet * 10 + digit - '0';
                if (valueOfOctet > 255) {
                    return null;
                }
            }
            result[i] = (byte) valueOfOctet;
        }
        return result;
    }

    static byte[] parseIpv6(String value) {
        int compression = value.indexOf("::");
        if (compression >= 0 && compression != value.lastIndexOf("::")) {
            return null;
        }
        boolean hasCompression = compression >= 0;
        String left = hasCompression ? value.substring(0, compression) : value;
        String right = hasCompression ? value.substring(compression + 2) : "";

        if (value.indexOf('.') >= 0) {
            int embeddedIpv4Index = value.lastIndexOf(':');
            if (embeddedIpv4Index < 0 || embeddedIpv4Index == value.length() - 1) {
                return null;
            }
            byte[] ipv4 = parseIpv4(value.substring(embeddedIpv4Index + 1));
            if (ipv4 == null) {
                return null;
            }
            String replacement = Integer.toHexString((Byte.toUnsignedInt(ipv4[0]) << 8)
                    | Byte.toUnsignedInt(ipv4[1])) + ":"
                    + Integer.toHexString((Byte.toUnsignedInt(ipv4[2]) << 8) | Byte.toUnsignedInt(ipv4[3]));
            value = value.substring(0, embeddedIpv4Index + 1) + replacement;
            compression = value.indexOf("::");
            hasCompression = compression >= 0;
            left = hasCompression ? value.substring(0, compression) : value;
            right = hasCompression ? value.substring(compression + 2) : "";
        }

        int[] leftGroups = parseHextets(left);
        int[] rightGroups = parseHextets(right);
        if (leftGroups == null || rightGroups == null) {
            return null;
        }
        int explicitGroups = leftGroups.length + rightGroups.length;
        if (hasCompression ? explicitGroups >= 8 : explicitGroups != 8) {
            return null;
        }
        int[] groups = new int[8];
        System.arraycopy(leftGroups, 0, groups, 0, leftGroups.length);
        System.arraycopy(rightGroups, 0, groups, 8 - rightGroups.length, rightGroups.length);
        byte[] result = new byte[16];
        for (int i = 0; i < groups.length; i++) {
            result[i * 2] = (byte) (groups[i] >>> 8);
            result[i * 2 + 1] = (byte) groups[i];
        }
        return result;
    }

    private static int[] parseHextets(String side) {
        if (side.isEmpty()) {
            return new int[0];
        }
        String[] values = side.split(":", -1);
        int[] result = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            String value = values[i];
            if (value.isEmpty() || value.length() > 4) {
                return null;
            }
            int parsed = 0;
            for (int j = 0; j < value.length(); j++) {
                int digit = Character.digit(value.charAt(j), 16);
                if (digit < 0) {
                    return null;
                }
                parsed = (parsed << 4) | digit;
            }
            result[i] = parsed;
        }
        return result;
    }

    static String format(IpLiteral literal) {
        byte[] bytes = literal.addressBytes();
        if (bytes.length == 4) {
            return (bytes[0] & 0xff) + "." + (bytes[1] & 0xff) + "." + (bytes[2] & 0xff) + "."
                    + (bytes[3] & 0xff);
        }
        int[] groups = new int[8];
        for (int i = 0; i < groups.length; i++) {
            groups[i] = ((bytes[i * 2] & 0xff) << 8) | (bytes[i * 2 + 1] & 0xff);
        }
        int bestStart = -1;
        int bestLength = 1;
        for (int i = 0; i < groups.length;) {
            if (groups[i] != 0) {
                i++;
                continue;
            }
            int end = i;
            while (end < groups.length && groups[end] == 0) {
                end++;
            }
            if (end - i > bestLength) {
                bestStart = i;
                bestLength = end - i;
            }
            i = end;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < groups.length; i++) {
            if (i == bestStart) {
                result.append("::");
                i += bestLength - 1;
                continue;
            }
            if (i > 0 && i != bestStart + bestLength) {
                result.append(':');
            }
            result.append(Integer.toHexString(groups[i]));
        }
        return result.toString();
    }
}
