package com.example.relay.deliveryengine.worker;

import com.example.relay.deliveryengine.config.DeliveryHttpClientConfig;
import com.example.relay.deliveryengine.destination.DnsResolutionException;
import com.example.relay.deliveryengine.destination.HostAddressLookup;
import com.example.relay.deliveryengine.destination.PolicyEnforcingDnsResolver;
import com.example.relay.deliveryengine.destination.PublicDestinationAddressPolicy;
import com.example.relay.deliveryengine.destination.SpecialPurposeAddressCatalog;
import com.example.relay.deliveryengine.http.ApacheWebhookHttpTransport;
import com.example.relay.deliveryengine.http.BoundedApacheResponseBodyConsumer;
import com.example.relay.deliveryengine.http.WebhookHttpTransport;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;

/** Test-only Apache transport that maps the single fixture hostname to loopback. */
@TestConfiguration(proxyBeanMethods = false)
class LoopbackWebhookTransportTestConfiguration {

    static final String FIXTURE_HOST = "webhook.test";
    private static final String ABSOLUTE_FIXTURE_HOST = FIXTURE_HOST + ".";

    @Bean(name = "loopbackTestDeliveryConnectionManager", destroyMethod = "close")
    @Primary
    PoolingHttpClientConnectionManager loopbackTestDeliveryConnectionManager() {
        DeliveryHttpClientConfig config = new DeliveryHttpClientConfig();
        HostAddressLookup lookup = (absoluteHostname, deadline) -> {
            if (!ABSOLUTE_FIXTURE_HOST.equals(absoluteHostname)) {
                throw new DnsResolutionException("unexpected test fixture hostname: " + absoluteHostname);
            }
            return List.of(InetAddress.getLoopbackAddress());
        };
        PolicyEnforcingDnsResolver resolver = new PolicyEnforcingDnsResolver(lookup, loopbackPermittingPolicy());
        return config.deliveryConnectionManager(resolver);
    }

    @Bean(name = "loopbackTestDeliveryApacheHttpClient", destroyMethod = "close")
    @Primary
    CloseableHttpClient loopbackTestDeliveryApacheHttpClient(
            @Qualifier("loopbackTestDeliveryConnectionManager") PoolingHttpClientConnectionManager manager) {
        return new DeliveryHttpClientConfig().deliveryApacheHttpClient(manager);
    }

    @Bean
    @Primary
    WebhookHttpTransport loopbackTestWebhookHttpTransport(
            @Qualifier("loopbackTestDeliveryApacheHttpClient") CloseableHttpClient client,
            @Qualifier("webhookDeadlineTaskScheduler") org.springframework.scheduling.TaskScheduler scheduler) {
        return new ApacheWebhookHttpTransport(client, new BoundedApacheResponseBodyConsumer(), scheduler);
    }

    private static PublicDestinationAddressPolicy loopbackPermittingPolicy() {
        try (InputStream resource = SpecialPurposeAddressCatalog.class
                .getResourceAsStream(SpecialPurposeAddressCatalog.RESOURCE)) {
            if (resource == null) {
                throw new IllegalStateException("missing pinned policy resource");
            }
            String fixtureCatalog = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))
                    .lines()
                    .filter(line -> !line.startsWith("127.0.0.0/8|LOOPBACK")
                            && !line.startsWith("::1/128|LOOPBACK"))
                    .collect(Collectors.joining("\n"));
            return new PublicDestinationAddressPolicy(new SpecialPurposeAddressCatalog(
                    new ByteArrayInputStream(fixtureCatalog.getBytes(StandardCharsets.UTF_8))));
        } catch (IOException exception) {
            throw new IllegalStateException("unable to read pinned policy resource", exception);
        }
    }
}
