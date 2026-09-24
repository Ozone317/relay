package com.example.relay.deliveryengine.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BoundedResponseBodyCaptureTest {

    @Test
    void oversizedBody_readsOnlyTheDiagnosticPrefixAndSentinel_withoutClosingTheStream() throws IOException {
        CountingInputStream input = new CountingInputStream(
                new ByteArrayInputStream("x".repeat(1_000_000).getBytes(StandardCharsets.UTF_8)));

        BoundedResponseBodyCapture.Capture capture = BoundedResponseBodyCapture.capture(input);

        assertEquals(10_241, input.bytesRead());
        assertFalse(input.closed());
        assertEquals(10_241, capture.bytesRead());
        assertTrue(capture.truncated());
        assertTrue(capture.body().length() <= 10_240);
        assertTrue(capture.body().endsWith("[relay response truncated at 10240 bytes]"));
    }

    @Test
    void exactLimitBody_isRetainedWithoutATruncationMarker() throws IOException {
        String body = "x".repeat(10_240);

        BoundedResponseBodyCapture.Capture capture = BoundedResponseBodyCapture.capture(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));

        assertEquals(body, capture.body());
        assertFalse(capture.truncated());
        assertEquals(10_240, capture.bytesRead());
    }

    @Test
    void oneByteBody_isReadExactlyOnce() throws IOException {
        BoundedResponseBodyCapture.Capture capture = BoundedResponseBodyCapture.capture(
                new ByteArrayInputStream(new byte[] {'x'}));

        assertEquals("x", capture.body());
        assertFalse(capture.truncated());
        assertEquals(1, capture.bytesRead());
    }

    @Test
    void bodyLargerThanSentinel_readsExactlyTheSentinel() throws IOException {
        byte[] body = new byte[20_000];

        BoundedResponseBodyCapture.Capture capture = BoundedResponseBodyCapture.capture(
                new ByteArrayInputStream(body));

        assertTrue(capture.truncated());
        assertEquals(10_241, capture.bytesRead());
    }

    @Test
    void emptyBody_isRepresentedAsNull() throws IOException {
        BoundedResponseBodyCapture.Capture capture = BoundedResponseBodyCapture.capture(
                InputStream.nullInputStream());

        assertNull(capture.body());
        assertFalse(capture.truncated());
    }

    @Test
    void malformedUtf8_isDecodedWithReplacementCharacters() throws IOException {
        byte[] malformed = {(byte) 0xC3, 0x28};

        BoundedResponseBodyCapture.Capture capture = BoundedResponseBodyCapture.capture(
                new ByteArrayInputStream(malformed));

        assertEquals("\uFFFD(", capture.body());
    }

    @Test
    void incompleteMultibyteSequenceAtCaptureBoundary_producesAValidString() throws IOException {
        byte[] body = new byte[10_240];
        java.util.Arrays.fill(body, (byte) 'x');
        body[10_239] = (byte) 0xE2;

        BoundedResponseBodyCapture.Capture capture = BoundedResponseBodyCapture.capture(
                new ByteArrayInputStream(body));

        assertEquals("x".repeat(10_239) + "\uFFFD", capture.body());
        assertFalse(capture.truncated());
        assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(capture.body()));
    }

    @Test
    void oversizedBodyWhoseMultibyteCharacterCrossesTheCaptureBoundary_remainsValidAndMarked() throws IOException {
        byte[] body = new byte[10_242];
        java.util.Arrays.fill(body, (byte) 'x');
        byte[] euro = "€".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(euro, 0, body, 10_239, euro.length);

        BoundedResponseBodyCapture.Capture capture = BoundedResponseBodyCapture.capture(
                new ByteArrayInputStream(body));

        assertTrue(capture.truncated());
        assertTrue(capture.body().endsWith("[relay response truncated at 10240 bytes]"));
        assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(capture.body()));
        assertFalse(hasUnpairedSurrogate(capture.body()));
    }

    @Test
    void bodyReadFailure_isPropagatedWithoutUtilityClosingTheStream() {
        FailingInputStream input = new FailingInputStream();

        assertThrows(IOException.class, () -> BoundedResponseBodyCapture.capture(input));

        assertFalse(input.closed());
    }

    private static final class CountingInputStream extends FilterInputStream {

        private int bytesRead;
        private boolean closed;

        private CountingInputStream(InputStream delegate) {
            super(delegate);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                bytesRead++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            if (count > 0) {
                bytesRead += count;
            }
            return count;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        private int bytesRead() {
            return bytesRead;
        }

        private boolean closed() {
            return closed;
        }
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private static final class FailingInputStream extends InputStream {

        private boolean closed;

        @Override
        public int read() throws IOException {
            throw new IOException("simulated body read failure");
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            throw new IOException("simulated body read failure");
        }

        @Override
        public void close() {
            closed = true;
        }

        private boolean closed() {
            return closed;
        }
    }
}
