package ua.carrental.payment;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.*;
import static org.assertj.core.api.Assertions.*;


class IdempotencyIntegrationTest {
    static ConfigurableApplicationContext context;
    static RestClient api;
    static JdbcClient jdbc;
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");


    @BeforeAll
    static void start() {

        context = SpringApplication.run(PaymentApplication.class,
                "--server.port=0", "--DB_URL=jdbc:h2:mem:payment;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                "--DB_USER=sa", "--DB_PASSWORD=", "--spring.main.banner-mode=off");
        api = RestClient.create("http://localhost:" + ((WebServerApplicationContext) context).getWebServer().getPort());
        jdbc = context.getBean(JdbcClient.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();

    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM idempotency_keys").update();
        jdbc.sql("DELETE FROM payments").update();

    }

    static Object request() {
        return new CreatePaymentRequest(ID, new java.math.BigDecimal("100.00"), "UAH");
    }

    static org.springframework.http.ResponseEntity<Payment> create(String key, Object body) {
        return api.post().uri("/api/payments").header("Idempotency-Key", key).body(body).retrieve().toEntity(Payment.class);
    }

    @Test
    void replayReturnsSameStatusBodyAndLocation() {
        var first = create("replay-key", request());
        var second = create("replay-key", request());
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(second.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payments").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsCreateOnlyOneRecord() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            List<Future<Payment>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                start.await();
                return create("concurrent-key", request()).getBody();
            }));
            start.countDown();
            Set<UUID> ids = new HashSet<>();
            for (var future : futures) ids.add(future.get(15, TimeUnit.SECONDS).id());
            assertThat(ids).hasSize(1);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM payments").query(Integer.class).single()).isEqualTo(1);
        }
    }

    @Test
    void changedPayloadForSameKeyReturnsConflict() {
        create("conflict-key", request());
        assertProblem(() -> create("conflict-key", new CreatePaymentRequest(ID, new java.math.BigDecimal("200.00"), "UAH")), 409);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payments").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void missingKeyMalformedJsonAndInvalidKeyReturnProblemDetail() {
        assertProblem(() -> api.post().uri("/api/payments").body(request()).retrieve().toBodilessEntity(), 400);
        assertProblem(() -> create("invalid key", request()), 400);
        assertProblem(() -> api.post().uri("/api/payments").header("Idempotency-Key", "bad-json")
                .header("Content-Type", "application/json").body("{").retrieve().toBodilessEntity(), 400);
        assertProblem(() -> create("bad-body", Map.of()), 400);
    }

    static void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(RestClientResponseException.class, ex -> {
            assertThat(ex.getStatusCode().value()).isEqualTo(status);
            assertThat(ex.getResponseHeaders().getContentType().toString()).isEqualTo("application/problem+json");
            var body = ex.getResponseBodyAs(ProblemDetail.class);
            assertThat(body.getStatus()).isEqualTo(status);
            assertThat(body.getType().toString()).isEqualTo("about:blank");
            assertThat(body.getTitle()).isNotBlank();
            assertThat(body.getInstance().toString()).isEqualTo("/api/payments");
            assertThat(body.getDetail()).isNotBlank();
        });
    }

}
