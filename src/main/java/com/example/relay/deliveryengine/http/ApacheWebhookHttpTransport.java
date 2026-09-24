package com.example.relay.deliveryengine.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

import com.example.relay.deliveryengine.destination.DestinationPolicyBlockedException;
import com.example.relay.deliveryengine.destination.DnsResolutionException;
import com.example.relay.endpoint.domain.InvalidWebhookUriException;
import com.example.relay.endpoint.domain.ParsedWebhookUri;
import com.example.relay.endpoint.domain.WebhookUriParser;

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
        HttpPost request = new HttpPost(destination.normalizedUri());
        request.setEntity(new ByteArrayEntity(body, ContentType.create("application/json")));
        request.setHeader("relay-id", headers.relayId());
        request.setHeader("relay-timestamp", Long.toString(headers.relayTimestamp()));
        request.setHeader("relay-signature", headers.relaySignature());
        applyTimeouts(request, deadline);

        ScheduledFuture<?> cancellation = scheduleCancellation(request, deadline);
        try (DeliveryDeadlineContext.Scope ignored = DeliveryDeadlineContext.open(deadline)) {
            try {
                if (client == null) {
                    throw new IllegalStateException("Apache webhook client is not configured");
                }
                try (CloseableHttpResponse response = client.execute(request)) {
                    if (deadline.remaining().isZero()) {
                        throw timeout("delivery deadline expired", null);
                    }
                    HttpEntity entity = response.getEntity();
                    InputStream bodyStream = entity == null ? InputStream.nullInputStream() : entity.getContent();
                    String responseBody = consumeWithinDeadline(bodyStream, request, deadline);
                    if (deadline.remaining().isZero()) {
                        throw timeout("delivery deadline expired", null);
                    }
                    return new WebhookHttpResponse(response.getCode(), responseBody);
                }
            } catch (WebhookDeliveryException exception) {
                throw exception;
            } catch (DestinationPolicyBlockedException exception) {
                throw failure(WebhookFailureCode.DESTINATION_POLICY_BLOCKED, "destination is blocked by policy",
                        exception);
            } catch (UnknownHostException exception) {
                throw failure(classifyDnsFailure(exception), "DNS resolution failed", exception);
            } catch (CancellationException exception) {
                throw timeout("delivery was cancelled", exception);
            } catch (ConnectTimeoutException | ConnectionRequestTimeoutException exception) {
                throw timeout("delivery deadline expired", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw timeout("delivery was interrupted", exception);
            } catch (IOException exception) {
                if (deadline.remaining().isZero() || request.isCancelled()) {
                    throw timeout("delivery deadline expired", exception);
                }
                WebhookFailureCode resolverCode = classifyResolverFailure(exception);
                if (resolverCode != null) {
                    throw failure(resolverCode, resolverCode == WebhookFailureCode.DESTINATION_POLICY_BLOCKED
                            ? "destination is blocked by policy" : "DNS resolution failed", exception);
                }
                throw failure(WebhookFailureCode.TRANSPORT_FAILURE, "webhook exchange failed", exception);
            }
        } finally {
            cancellation.cancel(false);
        }
    }

    private String consumeWithinDeadline(InputStream stream, HttpUriRequestBase request, DeliveryDeadline deadline)
            throws IOException, InterruptedException, WebhookDeliveryException {
        FutureTask<String> task = new FutureTask<>(() -> responseBodyConsumer.consume(stream));
        Thread.startVirtualThread(task);
        try {
            long remainingNanos = deadline.remaining().toNanos();
            if (remainingNanos <= 0) {
                request.cancel();
                task.cancel(true);
                throw timeout("delivery deadline expired", null);
            }
            return task.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            request.cancel();
            task.cancel(true);
            throw timeout("delivery deadline expired", exception);
        } catch (CancellationException exception) {
            throw timeout("delivery was cancelled", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Unexpected response consumer failure", cause);
        }
    }

    private ScheduledFuture<?> scheduleCancellation(HttpUriRequestBase request, DeliveryDeadline deadline) {
        long remainingNanos = deadline.remaining().toNanos();
        return deadlineScheduler.schedule(request::cancel, Math.max(0, remainingNanos), TimeUnit.NANOSECONDS);
    }

    private static void applyTimeouts(HttpUriRequestBase request, DeliveryDeadline deadline) {
        Timeout timeout = Timeout.of(deadline.remaining());
        request.setConfig(RequestConfig.custom().setConnectionRequestTimeout(timeout).setConnectTimeout(timeout)
                .setResponseTimeout(timeout).setRedirectsEnabled(false).build());
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
        private static final ScheduledExecutorService INSTANCE = java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "relay-webhook-deadline");
                    thread.setDaemon(true);
                    return thread;
                });
    }
}
