package ua.carrental.booking.catalog;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import ua.carrental.booking.BookingApplication;
import ua.carrental.booking.web.CorrelationIdInterceptor;
import static org.assertj.core.api.Assertions.*;

public class CatalogClientTest {
    static HttpServer mock;
    static ExecutorService executor;
    static WebServerApplicationContext context;
    static CatalogClient client;
    static final AtomicReference<String> received = new AtomicReference<>();
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID SLOW = UUID.fromString("00000000-0000-0000-0000-000000000005");

    @BeforeAll
    static void start() throws Exception {
        mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        mock.setExecutor(executor);
        mock.createContext("/api/vehicles/", exchange -> {
            received.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            String id = exchange.getRequestURI().getPath().substring("/api/vehicles/".length());
            try (exchange) {
                if (id.equals(SLOW.toString())) Thread.sleep(5000);
                byte[] body = json(id).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        mock.start();
        context = (WebServerApplicationContext) SpringApplication.run(BookingApplication.class,
                "--spring.profiles.active=http-demo", "--server.port=0",
                "--FLEET_URL=http://127.0.0.1:" + mock.getAddress().getPort());
        client = context.getBean(CatalogClient.class);
    }

    public static String json(String id) {
        return """
                {"id":"%s","brand":"Toyota","model":"Corolla","plate":"AA1234BB",
                 "dailyRate":1200.00,"futureField":{"unknown":true}}
                """.formatted(id);
    }

    @AfterAll
    static void stop() {
        if (context != null) ((org.springframework.context.ConfigurableApplicationContext) context).close();
        if (mock != null) mock.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @AfterEach
    void clean() { MDC.remove(CorrelationIdInterceptor.HEADER); }

    @Test
    void successAndCorrelationPropagation() {
        MDC.put(CorrelationIdInterceptor.HEADER, "test-success-42");
        var vehicle = client.getVehicle(ID);
        assertThat(vehicle.id()).isEqualTo(ID);
        assertThat(vehicle.brand()).isEqualTo("Toyota");
        assertThat(vehicle.dailyRate()).isEqualByComparingTo("1200.00");
        assertThat(received.get()).isEqualTo("test-success-42");
    }

    @Test
    void tolerantReaderWorksEvenWithStrictMapper() throws Exception {
        var mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        assertThat(mapper.readValue(json(ID.toString()), RentalCatalog.Vehicle.class).id()).isEqualTo(ID);
        assertThat(mapper.readValue("{\"customerId\":\"" + ID
                + "\",\"allowed\":true,\"newField\":42}", RentalCatalog.Eligibility.class).allowed()).isTrue();
    }

    @Test
    void createsCorrelationIdOutsideIncomingRequest() {
        client.getVehicle(ID);
        assertThat(UUID.fromString(received.get())).isNotNull();
        assertThat(MDC.get(CorrelationIdInterceptor.HEADER)).isNull();
    }

    @Test
    void incomingHeaderReachesDownstreamThroughController() {
        var response = RestClient.create("http://localhost:" + context.getWebServer().getPort())
                .get().uri("/demo/vehicles/" + ID).header("X-Correlation-Id", "incoming-123")
                .retrieve().toEntity(RentalCatalog.Vehicle.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("incoming-123");
        assertThat(received.get()).isEqualTo("incoming-123");
        var generated = RestClient.create("http://localhost:" + context.getWebServer().getPort())
                .get().uri("/demo/vehicles/" + ID).retrieve().toBodilessEntity();
        assertThat(received.get()).isEqualTo(generated.getHeaders().getFirst("X-Correlation-Id"));
        assertThat(received.get()).isNotEqualTo("incoming-123");
    }

    @Test
    void readTimeoutIsResourceAccessExceptionAfterThreeSeconds() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> client.getVehicle(SLOW))
                .isInstanceOf(ResourceAccessException.class).hasRootCauseInstanceOf(HttpTimeoutException.class);
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(elapsed).isBetween(2500L, 4500L);
        System.out.println("VERIFIED ResourceAccessException / HttpTimeoutException elapsedMs=" + elapsed);
    }
}
