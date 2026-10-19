package ua.carrental.payment;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP contract: each executable app exposes every metric type and health probes. */
class ObservabilityIntegrationTest {
    @Test
    void exposesFiveMeterTypesAndShowsActiveTaskLifecycle() throws Exception {
        try (var context = SpringApplication.run(PaymentApplication.class,
                "--server.port=0", "--spring.profiles.active=monitoring-demo",
                "--DB_URL=jdbc:h2:mem:observability-payment;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "--DB_USER=sa", "--DB_PASSWORD=", "--FLEET_URL=http://localhost:9",
                "--CUSTOMER_URL=http://localhost:9", "--messaging.enabled=false",
                "--spring.kafka.admin.auto-create=false", "--spring.main.banner-mode=off");
             var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            String base = "http://localhost:" + ((WebServerApplicationContext) context).getWebServer().getPort();
            for (String probe : new String[]{"/actuator/health/liveness", "/actuator/health/readiness"}) {
                assertThat(client.send(HttpRequest.newBuilder(URI.create(base + probe)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            }
            // Invalid business payload still exercises real request processing with a known byte size.
            var post = HttpRequest.newBuilder(URI.create(base + "/api/payments"))
                    .header("Content-Type", "application/json").header("Idempotency-Key", "metrics-probe")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"probe\":true}")).build();
            assertThat(client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
            var task = client.sendAsync(HttpRequest.newBuilder(URI.create(base + "/api/monitoring/demo-work?seconds=2"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            var registry = context.getBean(MeterRegistry.class);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (registry.get("car.rental.http.active.requests").longTaskTimer().activeTasks() == 0
                    && System.nanoTime() < deadline) Thread.sleep(10);
            String active = client.send(HttpRequest.newBuilder(URI.create(base + "/actuator/prometheus")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertThat(active).contains(
                    "car_rental_http_in_flight_requests{application=\"payment-service\"} 1.0",
                    "car_rental_http_active_requests_seconds_count{application=\"payment-service\"} 1",
                    "car_rental_http_active_requests_seconds_sum{application=\"payment-service\"}",
                    "car_rental_http_requests_total",
                    "car_rental_http_request_duration_seconds_bucket",
                    "car_rental_http_request_body_bytes_bucket",
                    "car_rental_http_request_body_bytes_sum{application=\"payment-service\"} 14.0");
            assertThat(task.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            String completed = client.send(HttpRequest.newBuilder(URI.create(base + "/actuator/prometheus")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertThat(completed).contains(
                    "car_rental_http_in_flight_requests{application=\"payment-service\"} 0.0",
                    "car_rental_http_active_requests_seconds_count{application=\"payment-service\"} 0",
                    "car_rental_http_requests_total{application=\"payment-service\",method=\"GET\",outcome=\"success\"} 1.0",
                    "car_rental_http_request_duration_seconds_count{application=\"payment-service\",outcome=\"success\"} 1");
        }
    }
}
