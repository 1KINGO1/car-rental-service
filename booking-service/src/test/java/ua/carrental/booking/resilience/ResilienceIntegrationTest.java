package ua.carrental.booking.resilience;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.web.client.*;
import org.springframework.http.ProblemDetail;
import ua.carrental.booking.BookingApplication;
import ua.carrental.booking.catalog.CatalogClient;
import ua.carrental.booking.catalog.CatalogClientTest;
import ua.carrental.booking.catalog.CatalogController;
import ua.carrental.booking.catalog.RentalCatalog;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class ResilienceIntegrationTest {
    static WireMockServer upstream;
    static ConfigurableApplicationContext context;
    static RentalCatalog catalog;
    static ResilientCalls calls;
    static RestClient api;
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeAll
    static void start() {
        upstream = new WireMockServer(options().dynamicPort());
        upstream.start();
        context = SpringApplication.run(BookingApplication.class,
                "--spring.profiles.active=http-demo", "--server.port=0",
                "--FLEET_URL=" + upstream.baseUrl(), "--CUSTOMER_URL=" + upstream.baseUrl(),
                "--remote-protection.initial-delay=10ms", "--remote-protection.max-concurrent-calls=2");
        catalog = context.getBean(RentalCatalog.class);
        calls = context.getBean(ResilientCalls.class);
        api = RestClient.create("http://localhost:" + ((WebServerApplicationContext) context).getWebServer().getPort());
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        if (upstream != null) upstream.stop();
    }

    @BeforeEach
    void reset() {
        upstream.resetAll();
        calls.breaker("fleet").reset();
        calls.breaker("customers").reset();
    }

    @Test
    void successfulBatchUsesOneRequestForManyIds() {
        upstream.stubFor(post(urlEqualTo("/api/vehicles/batch")).willReturn(okJson("[" + CatalogClientTest.json(ID.toString()) + "]")));
        var ids = java.util.stream.IntStream.range(0, 99).mapToObj(i -> UUID.randomUUID())
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        ids.add(ID);
        var result = catalog.vehicles(ids);
        assertThat(result.available()).isTrue();
        assertThat(result.vehicles()).extracting(RentalCatalog.Vehicle::id).containsExactly(ID);
        upstream.verify(1, postRequestedFor(urlEqualTo("/api/vehicles/batch")));
        assertThat(upstream.getAllServeEvents()).hasSize(1);
    }

    @Test
    void failureStormOpensBreakerAndFallbackStopsNetworkTraffic() {
        upstream.stubFor(post(urlEqualTo("/api/vehicles/batch")).willReturn(aResponse().withStatus(503)));
        for (int i = 0; i < 10; i++) {
            assertThat(catalog.vehicles(List.of(ID)).available()).isFalse();
        }
        assertThat(calls.breaker("fleet").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        upstream.verify(30, postRequestedFor(urlEqualTo("/api/vehicles/batch")));
        for (int i = 0; i < 50; i++) {
            var result = catalog.vehicles(List.of(ID));
            assertThat(result.available()).isFalse();
            assertThat(result.vehicles()).isEmpty();
        }
        upstream.verify(30, postRequestedFor(urlEqualTo("/api/vehicles/batch")));
    }

    @Test
    void opensAtExactlyFiftyPercentAndRecoversAfterProbes() {
        upstream.stubFor(post(urlEqualTo("/api/vehicles/batch")).willReturn(okJson("[]")));
        for (int i = 0; i < 5; i++) catalog.vehicles(List.of(ID));
        upstream.stubFor(post(urlEqualTo("/api/vehicles/batch")).willReturn(serverError()));
        for (int i = 0; i < 5; i++) catalog.vehicles(List.of(ID));
        assertThat(calls.breaker("fleet").getMetrics().getFailureRate()).isEqualTo(50f);
        assertThat(calls.breaker("fleet").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        calls.breaker("fleet").transitionToHalfOpenState();
        upstream.stubFor(post(urlEqualTo("/api/vehicles/batch")).willReturn(okJson("[]")));
        for (int i = 0; i < 3; i++) assertThat(catalog.vehicles(List.of(ID)).available()).isTrue();
        assertThat(calls.breaker("fleet").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void retriesTransientFailureThenReturnsSuccess() {
        upstream.stubFor(post(urlEqualTo("/api/vehicles/batch")).inScenario("recovery")
                .whenScenarioStateIs("Started").willReturn(serverError()).willSetStateTo("ready"));
        upstream.stubFor(post(urlEqualTo("/api/vehicles/batch")).inScenario("recovery")
                .whenScenarioStateIs("ready").willReturn(okJson("[]")));
        assertThat(catalog.vehicles(List.of(ID)).available()).isTrue();
        upstream.verify(2, postRequestedFor(urlEqualTo("/api/vehicles/batch")));
        assertThat(calls.breaker("fleet").getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    void clientErrorsAreNotRetriedAndProblemDetailIsReadable() {
        upstream.stubFor(get(urlEqualTo("/api/vehicles/" + ID)).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,\"detail\":\"Vehicle not found\"}")));
        assertThatThrownBy(() -> calls.execute("fleet", () -> context.getBean(CatalogClient.class).getVehicle(ID), () -> null))
                .isInstanceOfSatisfying(RestClientResponseException.class, ex -> {
                    assertThat(ex.getResponseBodyAs(ProblemDetail.class).getStatus()).isEqualTo(404);
                    assertThat(ex.getResponseBodyAs(ProblemDetail.class).getDetail()).isEqualTo("Vehicle not found");
                });
        upstream.verify(1, getRequestedFor(urlEqualTo("/api/vehicles/" + ID)));
        assertThat(calls.breaker("fleet").getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void batchesIdsAndPreservesCorrelationOnEveryRetry() {
        upstream.stubFor(post(urlEqualTo("/api/vehicles/batch")).willReturn(serverError()));
        var result = api.post().uri("/api/bookings/catalog").header("X-Correlation-Id", "batch-test")
                .body(new CatalogController.BatchRequest(List.of(ID, ID))).retrieve().toEntity(RentalCatalog.CatalogResult.class);
        assertThat(result.getBody().available()).isFalse();
        assertThat(result.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("batch-test");
        upstream.verify(3, postRequestedFor(urlEqualTo("/api/vehicles/batch"))
                .withHeader("X-Correlation-Id", equalTo("batch-test"))
                .withRequestBody(equalToJson("[\"" + ID + "\"]")));
    }

    @Test
    void bulkheadRejectsExcessConcurrentRequestsWithoutRetry() throws Exception {
        upstream.stubFor(post(urlEqualTo("/api/vehicles/batch")).willReturn(okJson("[]").withFixedDelay(1000)));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> catalog.vehicles(List.of(ID)));
            var second = pool.submit(() -> catalog.vehicles(List.of(ID)));
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (upstream.findAll(postRequestedFor(urlEqualTo("/api/vehicles/batch"))).size() < 2
                    && System.nanoTime() < deadline) Thread.sleep(10);
            upstream.verify(2, postRequestedFor(urlEqualTo("/api/vehicles/batch")));
            assertThat(catalog.vehicles(List.of(ID)).available()).isFalse();
            assertThat(first.get(5, TimeUnit.SECONDS).available()).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS).available()).isTrue();
        }
        upstream.verify(2, postRequestedFor(urlEqualTo("/api/vehicles/batch")));
        assertThat(calls.breaker("fleet").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void validationAndUnknownRoutesReturnProblemJson() {
        for (String path : List.of("/api/bookings/catalog", "/missing")) {
            assertThatThrownBy(() -> api.post().uri(path).body("{}").header("Content-Type", "application/json")
                    .retrieve().toBodilessEntity()).isInstanceOfSatisfying(RestClientResponseException.class, ex -> {
                        assertThat(ex.getResponseHeaders().getContentType().toString()).isEqualTo("application/problem+json");
                        assertThat(ex.getResponseBodyAs(ProblemDetail.class).getInstance().toString()).isEqualTo(path);
                    });
        }
    }
}
