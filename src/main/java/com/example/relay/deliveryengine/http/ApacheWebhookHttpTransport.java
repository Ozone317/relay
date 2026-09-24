package com.example.relay.deliveryengine.http;

import com.example.relay.deliveryengine.destination.DestinationPolicyBlockedException;
import com.example.relay.deliveryengine.destination.DnsResolutionException;
import com.example.relay.endpoint.domain.InvalidWebhookUriException;
import com.example.relay.endpoint.domain.ParsedWebhookUri;
import com.example.relay.endpoint.domain.WebhookUriParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

/** Delivery-only Apache exchange orchestration. */
public final class ApacheWebhookHttpTransport implements WebhookHttpTransport {

    static final Duration DELIVERY_DEADLINE = Duration.ofSeconds(15);

    private final CloseableHttpClient client;
    private final WebhookResponseBodyConsumer responseBodyConsumer;
    private final ScheduledExecutorService deadlineScheduler;
    private final WebhookUriParser uriParser;
    private final Duration deadlineBudget;

    public ApacheWebhookHttpTransport(CloseableHttpClient client, WebhookResponseBodyConsumer responseBodyConsumer) {
        this(client, responseBodyConsumer, DeadlineSchedulerHolder.INSTANCE);
    }

    public ApacheWebhookHttpTransport(CloseableHttpClient client, WebhookResponseBodyConsumer responseBodyConsumer,
            ScheduledExecutorService deadlineScheduler) {
        this(client, responseBodyConsumer, deadlineScheduler, new WebhookUriParser());
    }

    ApacheWebhookHttpTransport(CloseableHttpClient client, WebhookResponseBodyConsumer responseBodyConsumer,
            ScheduledExecutorService deadlineScheduler, WebhookUriParser uriParser) {
        this(client, responseBodyConsumer, deadlineScheduler, uriParser, DELIVERY_DEADLINE);
    }

    ApacheWebhookHttpTransport(CloseableHttpClient client, WebhookResponseBodyConsumer responseBodyConsumer,
            ScheduledExecutorService deadlineScheduler, WebhookUriParser uriParser, Duration deadlineBudget) {
        this.client = client;
        this.responseBodyConsumer = Objects.requireNonNull(responseBodyConsumer, "responseBodyConsumer");
        this.deadlineScheduler = Objects.requireNonNull(deadlineScheduler, "deadlineScheduler");
        this.uriParser = Objects.requireNonNull(uriParser, "uriParser");
        this.deadlineBudget = Objects.requireNonNull(deadlineBudget, "deadlineBudget");
    }

    @Override
    public WebhookHttpResponse post(String rawDestinationUrl, byte[] body, WebhookHeaders headers)
            throws WebhookDeliveryException {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(headers, "headers");

        ParsedWebhookUri destination;
        try {
            destination = uriParser.parse(rawDestinationUrl);
        } catch (InvalidWebhookUriException exception) {
            throw failure(WebhookFailureCode.DESTINATION_INVALID, "destination URI is invalid", exception);
        }

        DeliveryDeadline deadline = DeliveryDeadline.start(deadlineBudget, System::nanoTime);
        Duration componentTimeout = positiveRemaining(deadline);
        HttpPost request = new HttpPost(destination.normalizedUri());
        request.setEntity(new ByteArrayEntity(body, ContentType.create("application/json")));
        request.setHeader("relay-id", headers.relayId());
        request.setHeader("relay-timestamp", Long.toString(headers.relayTimestamp()));
        request.setHeader("relay-signature", headers.relaySignature());
        applyTimeouts(request, componentTimeout);

        ScheduledFuture<?> cancellation = scheduleCancellation(request, deadline);
        try {
            return consumeResponse(request, deadline);
        } catch (WebhookDeliveryException exception) {
            throw exception;
        } catch (CancellationException exception) {
            throw timeout("delivery was cancelled", exception);
        } catch (IOException exception) {
            if (deadline.remaining().isZero() || request.isCancelled()) {
                throw timeout("delivery deadline expired", exception);
            }
            throw failure(WebhookFailureCode.TRANSPORT_FAILURE, "webhook exchange failed", exception);
        } finally {
            cancellation.cancel(false);
        }
    }

    private WebhookHttpResponse consumeResponse(HttpUriRequestBase request, DeliveryDeadline deadline)
            throws WebhookDeliveryException, IOException {
        CloseableHttpResponse response = executeApache(request, deadline);
        ApacheResponseBodyOwnership ownership = responseBodyConsumer instanceof ApacheResponseBodyOwnership candidate
                ? candidate
                : null;
        boolean responseCloseAttempted = false;
        try {
            if (deadline.remaining().isZero() || request.isCancelled()) {
                throw timeout("delivery deadline expired", null);
            }
            HttpEntity entity = response.getEntity();
            InputStream bodyStream = entity == null ? InputStream.nullInputStream() : entity.getContent();
            String responseBody = consumeWithinDeadline(bodyStream, request, deadline);
            if (deadline.remaining().isZero() || request.isCancelled()) {
                throw timeout("delivery deadline expired", null);
            }
            WebhookHttpResponse result = new WebhookHttpResponse(response.getCode(), responseBody);
            responseCloseAttempted = true;
            closeResponse(response, ownership, null);
            return result;
        } catch (WebhookDeliveryException | IOException | RuntimeException exception) {
            if (!responseCloseAttempted) {
                closeResponse(response, ownership, exception);
            }
            throw exception;
        } catch (Error error) {
            if (!responseCloseAttempted) {
                closeResponse(response, ownership, error);
            }
            throw error;
        }
    }

    private static void closeResponse(CloseableHttpResponse response, ApacheResponseBodyOwnership ownership,
            Throwable primary) throws IOException {
        try {
            if (ownership != null && ownership.responseRequiresDiscard()) {
                response.close(CloseMode.IMMEDIATE);
            } else {
                response.close();
            }
        } catch (IOException closeException) {
            if (primary != null) {
                primary.addSuppressed(closeException);
            } else {
                throw closeException;
            }
        } finally {
            if (ownership != null) {
                ownership.clearResponseDisposition();
            }
        }
    }

    private CloseableHttpResponse executeApache(HttpUriRequestBase request, DeliveryDeadline deadline)
            throws WebhookDeliveryException, IOException {
        try (DeliveryDeadlineContext.Scope ignored = DeliveryDeadlineContext.open(deadline)) {
            try {
                if (client == null) {
                    throw new IllegalStateException("Apache webhook client is not configured");
                }
                return client.execute(request);
            } catch (DestinationPolicyBlockedException exception) {
                throw failure(WebhookFailureCode.DESTINATION_POLICY_BLOCKED, "destination is blocked by policy",
                        exception);
            } catch (UnknownHostException exception) {
                if (deadline.remaining().isZero() || request.isCancelled()) {
                    throw timeout("delivery deadline expired", exception);
                }
                throw failure(classifyDnsFailure(exception), "DNS resolution failed", exception);
            } catch (CancellationException exception) {
                throw timeout("delivery was cancelled", exception);
            } catch (ConnectTimeoutException | ConnectionRequestTimeoutException exception) {
                throw timeout("delivery deadline expired", exception);
            } catch (IOException exception) {
                if (deadline.remaining().isZero() || request.isCancelled()) {
                    throw timeout("delivery deadline expired", exception);
                }
                WebhookFailureCode resolverCode = classifyResolverFailure(exception);
                if (resolverCode != null) {
                    throw failure(resolverCode,
                            resolverCode == WebhookFailureCode.DESTINATION_POLICY_BLOCKED
                                    ? "destination is blocked by policy"
                                    : "DNS resolution failed",
                            exception);
                }
                throw failure(WebhookFailureCode.TRANSPORT_FAILURE, "webhook exchange failed", exception);
            }
        }
    }

    private String consumeWithinDeadline(InputStream stream, HttpUriRequestBase request, DeliveryDeadline deadline)
            throws IOException, WebhookDeliveryException {
        if (deadline.remaining().isZero() || deadline.remaining().isNegative() || request.isCancelled()) {
            request.cancel();
            closeQuietly(stream);
            throw timeout("delivery deadline expired", null);
        }
        return responseBodyConsumer.consume(stream);
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The exchange is already being canceled; the response owner closes it in the surrounding try block.
        }
    }

    private ScheduledFuture<?> scheduleCancellation(HttpUriRequestBase request, DeliveryDeadline deadline) {
        long remainingNanos = deadline.remaining().toNanos();
        return deadlineScheduler.schedule(request::cancel, Math.max(0, remainingNanos), TimeUnit.NANOSECONDS);
    }

    private static void applyTimeouts(HttpUriRequestBase request, Duration remaining) {
        Timeout timeout = Timeout.of(remaining);
        request.setConfig(RequestConfig.custom().setConnectionRequestTimeout(timeout).setConnectTimeout(timeout)
                .setResponseTimeout(timeout).setRedirectsEnabled(false).build());
    }

    private static Duration positiveRemaining(DeliveryDeadline deadline) throws WebhookDeliveryException {
        Duration remaining = deadline.remaining();
        if (remaining.isZero() || remaining.isNegative()) {
            throw timeout("delivery deadline expired", null);
        }
        return remaining;
    }

    private static WebhookFailureCode classifyDnsFailure(UnknownHostException exception) {
        WebhookFailureCode code = classifyResolverFailure(exception);
        return code == null ? WebhookFailureCode.DNS_RESOLUTION_FAILED : code;
    }

    private static WebhookFailureCode classifyResolverFailure(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof DestinationPolicyBlockedException) {
                return WebhookFailureCode.DESTINATION_POLICY_BLOCKED;
            }
            if (current instanceof DnsResolutionException) {
                return WebhookFailureCode.DNS_RESOLUTION_FAILED;
            }
            if (current instanceof UnknownHostException) {
                return WebhookFailureCode.DNS_RESOLUTION_FAILED;
            }
            current = current.getCause();
        }
        return null;
    }

    private static WebhookDeliveryException timeout(String diagnostic, Throwable cause) {
        return failure(WebhookFailureCode.DELIVERY_TIMEOUT, diagnostic, cause);
    }

    private static WebhookDeliveryException failure(WebhookFailureCode code, String diagnostic, Throwable cause) {
        return new WebhookDeliveryException(code, diagnostic, cause);
    }

    private static final class DeadlineSchedulerHolder {
        private static final ScheduledExecutorService INSTANCE =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "relay-webhook-deadline");
                    thread.setDaemon(true);
                    return thread;
                });
    }
}
