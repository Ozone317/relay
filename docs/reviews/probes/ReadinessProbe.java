// Review-only harness. P01 asserts fixed transport invariants; later probes characterize CURRENT defects.
// Not part of the application or default test suite. See second-pass report for invocation.
import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.common.ratelimit.PasswordResetRateLimiter;
import com.example.relay.common.security.AuthProperties;
import com.example.relay.common.security.SecureTokenGenerator;
import com.example.relay.delivery.application.DeliveryReplayService;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.delivery.infrastructure.DeliveryStatusRepository;
import com.example.relay.deliveryengine.config.DeliveryHttpClientConfig;
import com.example.relay.deliveryengine.config.RetrySchedulingConfig;
import com.example.relay.deliveryengine.publisher.AttemptPublisher;
import com.example.relay.deliveryengine.retry.RetryProperties;
import com.example.relay.deliveryengine.signing.HmacSigner;
import com.example.relay.deliveryengine.worker.DeliveryWorker;
import com.example.relay.email.EmailDispatchPublisher;
import com.example.relay.endpoint.api.dto.EndpointCreateDto;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.endpoint.infrastructure.EndpointRepository;
import com.example.relay.environment.domain.Environment;
import com.example.relay.environment.infrastructure.EnvironmentRepository;
import com.example.relay.event.domain.Event;
import com.example.relay.event.infrastructure.EventRepository;
import com.example.relay.message.domain.Message;
import com.example.relay.message.infrastructure.MessageRepository;
import com.example.relay.user.PasswordResetProperties;
import com.example.relay.user.application.PasswordResetService;
import com.example.relay.user.application.PasswordResetTokenService;
import com.example.relay.user.application.RefreshTokenService;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import com.example.relay.user.recovery.PasswordResetEmailRecoveryProperties;
import com.example.relay.user.recovery.PasswordResetEmailRecoverySweeper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.config.TaskSchedulerRouter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

public class ReadinessProbe {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.example.relay")
    @EnableJpaRepositories("com.example.relay")
    @EnableConfigurationProperties({AuthProperties.class, PasswordResetProperties.class})
    @Import({AttemptService.class, PasswordResetTokenService.class, RefreshTokenService.class,
            SecureTokenGenerator.class})
    static class DatabaseConfig {}

    static ConfigurableApplicationContext ctx;
    static AttemptRepository attempts;
    static AttemptService service;
    static JdbcTemplate jdbc;
    static final ObjectMapper json = new ObjectMapper();
    static final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    static <T> T bean(Class<T> type) { return ctx.getBean(type); }
    static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
    static void gate(CountDownLatch latch) {
        try { require(latch.await(10, TimeUnit.SECONDS), "latch deadline exceeded"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
    static void result(String name, String detail) { System.out.println("PROBE " + name + " " + detail); }

    record Fixture(User user, Environment environment, App app, Delivery delivery, Attempt attempt) {}
    static Fixture fixture(String url, String text, int number, AttemptStatus status) {
        User user = bean(UserRepository.class).save(new User(UUID.randomUUID() + "@probe.invalid", "hash"));
        Environment env = bean(EnvironmentRepository.class).save(new Environment("probe", "review", user));
        App app = bean(AppRepository.class).save(new App("probe", env));
        Event event = bean(EventRepository.class).save(new Event("probe", app));
        Endpoint endpoint = bean(EndpointRepository.class).save(new Endpoint("probe", url, "whsec_probe", app));
        Message message = bean(MessageRepository.class).save(new Message(app, event, json.createObjectNode().put("text", text)));
        Delivery delivery = bean(DeliveryRepository.class).save(new Delivery(app, message, endpoint));
        Attempt a = new Attempt(app, message, endpoint, delivery, number);
        a.setStatus(status);
        return new Fixture(user, env, app, delivery, attempts.saveAndFlush(a));
    }
    static DeliveryWorker worker() {
        return new DeliveryWorker(attempts, service, new HmacSigner(),
                new DeliveryHttpClientConfig().deliveryRestClient(), Mockito.mock(AttemptPublisher.class),
                threads, Clock.systemUTC(), new RetryProperties(), max -> Duration.ZERO);
    }
    static DeliveryReplayService replay(AttemptService attemptService) {
        EntityManager em = SharedEntityManagerCreator.createSharedEntityManager(bean(EntityManagerFactory.class));
        return new DeliveryReplayService(bean(DeliveryRepository.class), bean(DeliveryStatusRepository.class),
                attempts, attemptService, em);
    }

    static void transportAndResponse() throws Exception {
        AtomicReference<byte[]> wire = new AtomicReference<>();
        AtomicReference<String> type = new AtomicReference<>();
        AtomicReference<String> relayId = new AtomicReference<>();
        AtomicReference<String> signature = new AtomicReference<>();
        AtomicReference<String> timestamp = new AtomicReference<>();
        AtomicReference<String> response = new AtomicReference<>("ok");
        AtomicInteger status = new AtomicInteger(200), calls = new AtomicInteger(), trap = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            wire.set(exchange.getRequestBody().readAllBytes());
            type.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            relayId.set(exchange.getRequestHeaders().getFirst("relay-id"));
            signature.set(exchange.getRequestHeaders().getFirst("relay-signature"));
            timestamp.set(exchange.getRequestHeaders().getFirst("relay-timestamp"));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/trap");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/trap", exchange -> { trap.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try (var validator = Validation.buildDefaultValidatorFactory()) {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            require(validator.getValidator().validate(new EndpointCreateDto("internal", url)).isEmpty(), "loopback validation changed");
            var unicode = fixture(url, "café 世界", 1, AttemptStatus.CREATED);
            worker().onMessage(unicode.attempt().getId().toString()).get(10, TimeUnit.SECONDS);
            byte[] expected = unicode.attempt().getMessage().getBody().toString().getBytes(StandardCharsets.UTF_8);
            require(Arrays.equals(wire.get(), expected), "Unicode UTF-8 bytes did not match");
            require("application/json".equalsIgnoreCase(type.get()), "expected application/json Content-Type");
            require(new HmacSigner().sign(relayId.get(), Long.parseLong(timestamp.get()),
                    wire.get(), "whsec_probe").equals(signature.get()), "receiver signature did not verify over raw bytes");
            result("unicode", "contentType=" + type.get() + " utf8Matches=true receiverSignatureMatches=true");
            result("ssrf", "loopbackDtoAccepted=true actualWorkerReachedLoopback=true");

            var ascii = fixture(url, "ascii", 1, AttemptStatus.CREATED);
            worker().onMessage(ascii.attempt().getId().toString()).get(10, TimeUnit.SECONDS);
            byte[] asciiExpected = ascii.attempt().getMessage().getBody().toString().getBytes(StandardCharsets.UTF_8);
            require(Arrays.equals(wire.get(), asciiExpected), "ASCII UTF-8 bytes did not match");
            require("application/json".equalsIgnoreCase(type.get()), "expected application/json Content-Type");
            require(new HmacSigner().sign(relayId.get(), Long.parseLong(timestamp.get()),
                    wire.get(), "whsec_probe").equals(signature.get()), "ASCII receiver signature did not verify over raw bytes");
            result("ascii-control", "contentType=" + type.get() + " utf8Matches=true receiverSignatureMatches=true");

            response.set("x".repeat(11000));
            var large = fixture(url, "large", 1, AttemptStatus.CREATED);
            int before = calls.get();
            for (int i = 0; i < 2; i++) {
                try { worker().onMessage(large.attempt().getId().toString()).get(10, TimeUnit.SECONDS); throw new AssertionError("expected persistence failure"); }
                catch (ExecutionException e) {
                    require(e.getCause() instanceof org.springframework.dao.DataIntegrityViolationException,
                            "unexpected failure " + e.getCause());
                }
                require(attempts.findById(large.attempt().getId()).orElseThrow().getStatus() == AttemptStatus.IN_FLIGHT,
                        "expected durable IN_FLIGHT after rollback");
                if (i == 0) {
                    jdbc.update("UPDATE attempts SET updated_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes' WHERE id = ?", large.attempt().getId());
                    require(service.resetStuck(large.attempt().getId(), Instant.now().minusSeconds(90), Instant.now()) == 1, "reset did not win");
                }
            }
            result("large-success", "http200Calls=" + (calls.get() - before) + " status=IN_FLIGHT sameAttemptNo=1");
            status.set(500);
            var failed = fixture(url, "failure-control", 1, AttemptStatus.CREATED);
            worker().onMessage(failed.attempt().getId().toString()).get(10, TimeUnit.SECONDS);
            Attempt persisted = attempts.findById(failed.attempt().getId()).orElseThrow();
            require(persisted.getStatus() == AttemptStatus.FAILED_RETRYING && persisted.getResponseBody().length() == 10240,
                    "failure truncation control failed");
            result("large-failure-control", "status=FAILED_RETRYING capturedCharacters=10240");
            var redirect = fixture(url + "redirect", "redirect", 1, AttemptStatus.CREATED);
            worker().onMessage(redirect.attempt().getId().toString()).get(10, TimeUnit.SECONDS);
            require(trap.get() == 0 && attempts.findById(redirect.attempt().getId()).orElseThrow().getResponseCode() == 302,
                    "redirect control failed");
            result("redirect-control", "followed=false recordedStatus=302");
        } finally { server.stop(0); }
    }

    static void staleWorker() {
        var f = fixture("https://example.invalid/", "stale", 1, AttemptStatus.CREATED);
        UUID id = f.attempt().getId();
        require(service.claim(id, Instant.now().minusSeconds(120)), "A claim");
        Attempt a = attempts.findById(id).orElseThrow();
        require(service.resetStuck(id, Instant.now().minusSeconds(90), Instant.now()) == 1, "recovery claim");
        require(service.claim(id, Instant.now()), "B claim");
        service.markSucceeded(attempts.findById(id).orElseThrow(), 200, "B accepted", 1L);
        require(attempts.findById(id).orElseThrow().getStatus() == AttemptStatus.SUCCEEDED, "B completion");
        service.markFailedAndCreateRetry(a, Instant.now().plusSeconds(30), 500, "A late failure", null, 1L);
        require(attempts.findById(id).orElseThrow().getStatus() == AttemptStatus.FAILED_RETRYING, "stale write did not overwrite");
        require(jdbc.queryForObject("SELECT count(*) FROM attempts WHERE delivery_id = ? AND status = 'SCHEDULED'", Long.class,
                f.delivery().getId()) == 1L, "expected new child");
        result("stale-worker", "B_SUCCEEDED_overwritten_by_A_FAILED_RETRYING=true scheduledChild=1");
    }

    static void staleReplay() throws Exception {
        var f = fixture("https://example.invalid/", "replay", 6, AttemptStatus.DEAD);
        CountDownLatch readOld = new CountDownLatch(1), resume = new CountDownLatch(1);
        AttemptService delayed = new AttemptService(attempts, bean(DeliveryRepository.class)) {
            @Override public Attempt createReplay(Attempt old) { readOld.countDown(); gate(resume); return service.createReplay(old); }
        };
        var waiting = threads.submit(() -> replay(delayed).replay(f.delivery().getId(), f.app().getId(), f.environment().getId(), f.user().getId()));
        gate(readOld);
        try {
            var first = replay(service).replay(f.delivery().getId(), f.app().getId(), f.environment().getId(), f.user().getId());
            require(service.claim(first.getLatestAttemptId(), Instant.now()), "fast replay claim");
            service.markSucceeded(attempts.findById(first.getLatestAttemptId()).orElseThrow(), 200, "fast success", 1L);
        } finally { resume.countDown(); }
        waiting.get(10, TimeUnit.SECONDS);
        long duplicates = jdbc.queryForObject("SELECT count(*) FROM attempts WHERE delivery_id = ? AND attempt_no = 7", Long.class, f.delivery().getId());
        require(duplicates == 2, "expected duplicate sequence");
        result("stale-replay", "attemptNo7Rows=" + duplicates + " newReplayAfterSuccess=true");
    }

    static void staleResetRecovery() throws Exception {
        var user = bean(UserRepository.class).save(new User(UUID.randomUUID() + "@probe.invalid", "hash"));
        var tokens = bean(PasswordResetTokenService.class);
        var repository = bean(PasswordResetTokenRepository.class);
        var old = tokens.issue(user, Instant.now().minusSeconds(120));
        CountDownLatch selected = new CountDownLatch(1), resume = new CountDownLatch(1);
        var wrapped = Mockito.mock(PasswordResetTokenRepository.class, invocation -> {
            Object returned = AdditionalAnswers.delegatesTo(repository).answer(invocation);
            if (invocation.getMethod().getName().startsWith("findByResetEmailDispatchedAt")) { selected.countDown(); gate(resume); }
            return returned;
        });
        var resetService = new PasswordResetService(bean(UserRepository.class), tokens,
                Mockito.mock(PasswordResetRateLimiter.class), Mockito.mock(EmailDispatchPublisher.class),
                bean(PasswordResetProperties.class), Mockito.mock(PasswordEncoder.class));
        var sweeper = new PasswordResetEmailRecoverySweeper(wrapped, resetService, tokens, new PasswordResetEmailRecoveryProperties());
        var sweep = threads.submit(sweeper::sweep);
        gate(selected);
        var fresh = tokens.issue(user, Instant.now());
        Integer confirmed = new TransactionTemplate(bean(PlatformTransactionManager.class))
                .execute(s -> repository.claimResetEmailDispatch(fresh.token().getId(), Instant.now()));
        require(Integer.valueOf(1).equals(confirmed), "fresh reset dispatch confirmation did not win");
        require(repository.findById(fresh.token().getId()).orElseThrow().getResetEmailDispatchedAt() != null,
                "fresh reset dispatch was not durably confirmed");
        resume.countDown(); sweep.get(10, TimeUnit.SECONDS);
        require(repository.findById(fresh.token().getId()).orElseThrow().getUsedAt() != null, "fresh delivered link was not invalidated");
        require(repository.findById(old.token().getId()).orElseThrow().getUsedAt() != null, "old superseded token");
        result("stale-reset-recovery", "freshConfirmedTokenInvalidated=true singletonSweeperPlusUserRequest=true");
    }

    static void scheduler() throws Exception {
        try (var scheduling = new AnnotationConfigApplicationContext(RetrySchedulingConfig.class)) {
            var router = new TaskSchedulerRouter(); router.setBeanFactory(scheduling.getBeanFactory());
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicReference<String> unqualified = new AtomicReference<>();
            router.schedule(() -> { unqualified.set(Thread.currentThread().getName()); entered.countDown(); gate(release); }, Instant.now());
            gate(entered);
            var qualified = scheduling.getBean("retryTaskScheduler", TaskScheduler.class);
            var tick = qualified.schedule(() -> {}, Instant.now());
            require(!tick.isDone(), "qualified tick should be blocked by the unqualified job");
            release.countDown(); tick.get(10, TimeUnit.SECONDS); router.destroy();
            result("scheduler", "unqualifiedThread=" + unqualified.get() + " qualifiedTickBlockedUntilRelease=true");
        }
    }

    public static void main(String[] args) throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16")) {
            pg.start();
            try (var app = new SpringApplicationBuilder(DatabaseConfig.class).run(
                    "--spring.main.web-application-type=none", "--spring.config.location=optional:file:/nonexistent-readiness-probe.properties",
                    "--spring.datasource.url=" + pg.getJdbcUrl(), "--spring.datasource.username=" + pg.getUsername(),
                    "--spring.datasource.password=" + pg.getPassword(), "--spring.jpa.hibernate.ddl-auto=validate",
                    "--spring.jpa.show-sql=false", "--logging.level.root=WARN", "--logging.level.org.hibernate.SQL=OFF",
                    "--logging.level.org.springframework.jdbc=OFF", "--relay.password-reset.base-url=https://probe.invalid/reset")) {
                ctx = app; attempts = bean(AttemptRepository.class); service = bean(AttemptService.class); jdbc = bean(JdbcTemplate.class);
                transportAndResponse(); staleWorker(); staleReplay(); staleResetRecovery(); scheduler();
                result("COMPLETE", "allExpectedOutcomesObserved=true productionFilesModified=false");
            }
        } finally { threads.shutdownNow(); }
    }
}
