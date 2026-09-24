package com.example.relay.deliveryengine.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hc.core5.http.io.EofSensorInputStream;
import org.apache.hc.core5.http.io.EofSensorWatcher;
import org.junit.jupiter.api.Test;

class BoundedApacheResponseBodyConsumerTest {

    @Test
    void smallBodyReadsToEofWithoutAbort() throws IOException {
        TrackingWatcher watcher = new TrackingWatcher();
        EofSensorInputStream stream = new EofSensorInputStream(
                new ByteArrayInputStream("ok".getBytes(java.nio.charset.StandardCharsets.UTF_8)), watcher);

        String body = new BoundedApacheResponseBodyConsumer().consume(stream);

        assertEquals("ok", body);
        assertTrue(watcher.eof.get());
        assertFalse(watcher.aborted.get());
    }

    @Test
    void oversizedBodyAbortsEofSensorBeforeReturning() throws IOException {
        TrackingWatcher watcher = new TrackingWatcher();
        EofSensorInputStream stream = new EofSensorInputStream(
                new ByteArrayInputStream(new byte[20_000]), watcher);

        String body = new BoundedApacheResponseBodyConsumer().consume(stream);

        assertTrue(body.endsWith("[relay response truncated at 10240 bytes]"));
        assertTrue(watcher.aborted.get());
        assertFalse(watcher.eof.get());
    }

    @Test
    void bodyReadFailurePropagatesAndAbortsEofSensor() {
        TrackingWatcher watcher = new TrackingWatcher();
        EofSensorInputStream stream = new EofSensorInputStream(new FailingInputStream(), watcher);

        assertThrows(IOException.class, () -> new BoundedApacheResponseBodyConsumer().consume(stream));
        assertTrue(watcher.aborted.get());
    }

    @Test
    void oversizedNonApacheStreamFailsClosedInsteadOfGracefullyClosing() {
        InputStream stream = new ByteArrayInputStream(new byte[20_000]);

        assertThrows(IOException.class, () -> new BoundedApacheResponseBodyConsumer().consume(stream));
    }

    private static final class TrackingWatcher implements EofSensorWatcher {
        private final AtomicBoolean eof = new AtomicBoolean();
        private final AtomicBoolean aborted = new AtomicBoolean();

        @Override
        public boolean eofDetected(InputStream wrapped) {
            eof.set(true);
            return false;
        }

        @Override
        public boolean streamClosed(InputStream wrapped) {
            return false;
        }

        @Override
        public boolean streamAbort(InputStream wrapped) {
            aborted.set(true);
            return false;
        }
    }

    private static final class FailingInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            throw new IOException("body read failed");
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            throw new IOException("body read failed");
        }
    }
}
