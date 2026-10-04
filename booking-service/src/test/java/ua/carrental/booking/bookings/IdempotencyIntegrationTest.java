package ua.carrental.booking.bookings;

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
import com.github.tomakehurst.wiremock.WireMockServer;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import ua.carrental.booking.BookingApplication;
import ua.carrental.booking.catalog.CatalogClientTest;
import ua.carrental.booking.resilience.ResilientCalls;

class IdempotencyIntegrationTest {
    static ConfigurableApplicationContext context;
    static RestClient api;
    static JdbcClient jdbc;
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static WireMockServer upstream;

    @BeforeAll
    static void start() {
        upstream = new WireMockServer(options().dynamicPort());
        upstream.start();
        context = SpringApplication.run(BookingApplication.class,
                "--server.port=0", "--DB_URL=jdbc:h2:mem:booking;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                "--DB_USER=sa", "--DB_PASSWORD=", "--spring.main.banner-mode=off", "--FLEET_URL=" + upstream.baseUrl(), "--CUSTOMER_URL=" + upstream.baseUrl(), "--remote-protection.initial-delay=10ms");
        api = RestClient.create("http://localhost:" + ((WebServerApplicationContext) context).getWebServer().getPort());
        jdbc = context.getBean(JdbcClient.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        if (upstream != null) upstream.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM idempotency_keys").update();
        jdbc.sql("DELETE FROM bookings").update();
        upstream.resetAll();
        context.getBean(ResilientCalls.class).breaker("fleet").reset();
        upstream.stubFor(get(urlEqualTo("/api/customers/" + ID + "/eligibility"))
                .willReturn(okJson("{\"customerId\":\"" + ID + "\",\"allowed\":true}")));
        upstream.stubFor(get(urlEqualTo("/api/vehicles/" + ID)).willReturn(okJson(CatalogClientTest.json(ID.toString()))));
    }

    static Object request() {
        return new CreateBookingRequest(ID, ID, java.time.LocalDate.now().plusDays(1), java.time.LocalDate.now().plusDays(3));
    }

    static org.springframework.http.ResponseEntity<Booking> create(String key, Object body) {
        return api.post().uri("/api/bookings").header("Idempotency-Key", key).body(body).retrieve().toEntity(Booking.class);
    }

    @Test
    void replayReturnsSameStatusBodyAndLocation() {
        var first = create("replay-key", request());
        var second = create("replay-key", request());
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(second.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM bookings").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsCreateOnlyOneRecord() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            List<Future<Booking>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                start.await();
                return create("concurrent-key", request()).getBody();
            }));
            start.countDown();
            Set<UUID> ids = new HashSet<>();
            for (var future : futures) ids.add(future.get(15, TimeUnit.SECONDS).id());
            assertThat(ids).hasSize(1);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM bookings").query(Integer.class).single()).isEqualTo(1);
        }
    }

    @Test
    void changedPayloadForSameKeyReturnsConflict() {
        create("conflict-key", request());
        assertProblem(() -> create("conflict-key", new CreateBookingRequest(ID, ID, java.time.LocalDate.now().plusDays(1), java.time.LocalDate.now().plusDays(4))), 409);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM bookings").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void missingKeyMalformedJsonAndInvalidKeyReturnProblemDetail() {
        assertProblem(() -> api.post().uri("/api/bookings").body(request()).retrieve().toBodilessEntity(), 400);
        assertProblem(() -> create("invalid key", request()), 400);
        assertProblem(() -> api.post().uri("/api/bookings").header("Idempotency-Key", "bad-json")
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
            assertThat(body.getInstance().toString()).isEqualTo("/api/bookings");
            assertThat(body.getDetail()).isNotBlank();
        });
    }
    @Test
    void failedDependencyRollsBackKeyAndBookingAndAllowsRetry() {
        upstream.stubFor(get(urlEqualTo("/api/vehicles/" + ID)).willReturn(serverError()));
        assertProblem(() -> create("recover-key", request()), 503);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM idempotency_keys").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM bookings").query(Integer.class).single()).isZero();
        upstream.stubFor(get(urlEqualTo("/api/vehicles/" + ID)).willReturn(okJson(CatalogClientTest.json(ID.toString()))));
        assertThat(create("recover-key", request()).getStatusCode().value()).isEqualTo(201);
    }

}
