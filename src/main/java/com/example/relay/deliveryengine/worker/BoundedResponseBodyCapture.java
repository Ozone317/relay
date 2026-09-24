package com.example.relay.deliveryengine.worker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Captures a UTF-8 diagnostic prefix without materializing an unbounded receiver response.
 */
public final class BoundedResponseBodyCapture {

    static final int RETAINED_BYTE_LIMIT = 10_240;
    static final int PERSISTED_CHARACTER_LIMIT = 10_240;
    private static final String TRUNCATION_MARKER = "\n[relay response truncated at 10240 bytes]";

    private BoundedResponseBodyCapture() {
    }

    public static Capture capture(InputStream responseBody) throws IOException {
        // One extra byte distinguishes an exact-limit body from an oversized one without
        // relying on Content-Length, which may be absent or incorrect.
        byte[] bytes = new byte[RETAINED_BYTE_LIMIT + 1];
        int count = 0;
        while (count < bytes.length) {
            int read = responseBody.read(bytes, count, bytes.length - count);
            if (read == -1) {
                break;
            }
            count += read;
        }

        if (count == 0) {
            return new Capture(null, false, 0);
        }

        boolean truncated = count > RETAINED_BYTE_LIMIT;
        int retainedCount = Math.min(count, RETAINED_BYTE_LIMIT);
        String decoded = new String(bytes, 0, retainedCount, StandardCharsets.UTF_8);
        return new Capture(truncated ? markTruncated(decoded) : decoded, truncated, count);
    }

    static Capture captureAndClose(InputStream responseBody) throws IOException {
        // Legacy JDK RestClient ownership still requires a graceful close. Apache ownership
        // uses capture(InputStream) and decides between EOF release and abort/discard itself.
        try (responseBody) {
            return capture(responseBody);
        }
    }

    private static String markTruncated(String decoded) {
        int prefixLimit = PERSISTED_CHARACTER_LIMIT - TRUNCATION_MARKER.length();
        int prefixEnd = Math.min(decoded.length(), prefixLimit);
        if (prefixEnd > 0 && Character.isHighSurrogate(decoded.charAt(prefixEnd - 1))) {
            prefixEnd--;
        }
        return decoded.substring(0, prefixEnd) + TRUNCATION_MARKER;
    }

    public record Capture(String body, boolean truncated, int bytesRead) {
    }
}
