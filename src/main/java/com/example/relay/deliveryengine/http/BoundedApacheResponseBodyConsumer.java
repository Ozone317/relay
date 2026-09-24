package com.example.relay.deliveryengine.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import org.apache.hc.core5.http.io.EofSensorInputStream;

import com.example.relay.deliveryengine.worker.BoundedResponseBodyCapture;

/** Bounded response diagnostics with Apache EOF/reuse versus abort/discard ownership. */
public final class BoundedApacheResponseBodyConsumer
        implements WebhookResponseBodyConsumer, ApacheResponseBodyOwnership {

    private final ThreadLocal<Boolean> discardResponse = ThreadLocal.withInitial(() -> false);

    @Override
    public String consume(InputStream body) throws IOException {
        Objects.requireNonNull(body, "body");
        discardResponse.set(false);
        BoundedResponseBodyCapture.Capture capture;
        try {
            capture = BoundedResponseBodyCapture.capture(body);
        } catch (IOException exception) {
            discardResponse.set(true);
            abortIfApacheStream(body);
            throw exception;
        }
        if (capture.truncated()) {
            discardResponse.set(true);
            if (!(body instanceof EofSensorInputStream eofSensor)) {
                throw new IOException("truncated Apache response is not abort-capable");
            }
            eofSensor.abort();
        }
        return capture.body();
    }

    @Override
    public boolean responseRequiresDiscard() {
        return discardResponse.get();
    }

    @Override
    public void clearResponseDisposition() {
        discardResponse.remove();
    }

    private static void abortIfApacheStream(InputStream body) throws IOException {
        if (body instanceof EofSensorInputStream eofSensor) {
            eofSensor.abort();
        }
    }
}

interface ApacheResponseBodyOwnership {

    boolean responseRequiresDiscard();

    void clearResponseDisposition();
}
