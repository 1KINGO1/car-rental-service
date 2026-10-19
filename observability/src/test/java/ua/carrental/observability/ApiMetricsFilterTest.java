package ua.carrental.observability;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.servlet.ServletException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class ApiMetricsFilterTest {
    @Test
    void scrapeDistinguishesActiveTasksFromCompletedCallsAndRecordsBytes() throws Exception {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try (AutoCloseable closeRegistry = registry::close;
             var executor = Executors.newSingleThreadExecutor()) {
            registry.config().commonTags("application", "test-service");
            var filter = new ApiMetricsFilter(registry);
            var request = new MockHttpServletRequest("POST", "/api/customers");
            request.setContent(new byte[42]);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var call = executor.submit(() -> {
                filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new ServletException("Test task timeout");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new ServletException(exception);
                    }
                });
                return null;
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(registry.get("car.rental.http.in.flight.requests").gauge().value()).isEqualTo(1);
                assertThat(registry.get("car.rental.http.active.requests").longTaskTimer().activeTasks()).isEqualTo(1);
                assertThat(registry.get("car.rental.http.request.duration").tag("outcome", "success").timer().count()).isZero();
                assertThat(registry.scrape()).contains(
                        "car_rental_http_active_requests_seconds_count{application=\"test-service\"} 1",
                        "car_rental_http_active_requests_seconds_sum{application=\"test-service\"}",
                        "car_rental_http_active_requests_seconds_max{application=\"test-service\"}");
            } finally {
                release.countDown();
            }
            call.get(5, TimeUnit.SECONDS);
            assertThat(registry.get("car.rental.http.in.flight.requests").gauge().value()).isZero();
            assertThat(registry.get("car.rental.http.active.requests").longTaskTimer().activeTasks()).isZero();
            assertThat(registry.get("car.rental.http.requests").tags("method", "POST", "outcome", "success").counter().count()).isEqualTo(1);
            assertThat(registry.get("car.rental.http.request.duration").tag("outcome", "success").timer().count()).isEqualTo(1);
            assertThat(registry.get("car.rental.http.request.body").summary().totalAmount()).isEqualTo(42);
            assertThat(registry.scrape()).contains("car_rental_http_request_body_bytes_bucket", "car_rental_http_request_duration_seconds_bucket");
        }
    }

    @Test
    void exceptionalRequestsReleaseGaugesAndActuatorScrapesDoNotMeasureThemselves() throws Exception {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try (AutoCloseable closeRegistry = registry::close) {
            var filter = new ApiMetricsFilter(registry);
            assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/api/vehicles"),
                    new MockHttpServletResponse(), (req, res) -> { throw new ServletException("failure"); }))
                    .isInstanceOf(ServletException.class);
            assertThat(registry.get("car.rental.http.requests").tags("method", "GET", "outcome", "server_error").counter().count()).isEqualTo(1);
            assertThat(registry.get("car.rental.http.in.flight.requests").gauge().value()).isZero();
            assertThat(registry.get("car.rental.http.active.requests").longTaskTimer().activeTasks()).isZero();
            filter.doFilter(new MockHttpServletRequest("GET", "/actuator/prometheus"), new MockHttpServletResponse(), (req, res) -> {});
            assertThat(registry.get("car.rental.http.requests").tags("method", "GET", "outcome", "success").counter().count()).isZero();
        }
    }

    @Test
    void arbitraryHttpMethodsCannotCreateUnboundedLabelValues() throws Exception {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try (AutoCloseable closeRegistry = registry::close) {
            var filter = new ApiMetricsFilter(registry);
            var response = new MockHttpServletResponse();
            response.setStatus(405);
            filter.doFilter(new MockHttpServletRequest("CUSTOM-USER-ID", "/api/private-id"), response, (req, res) -> {});
            assertThat(registry.get("car.rental.http.requests").tags("method", "OTHER", "outcome", "client_error").counter().count()).isEqualTo(1);
            assertThat(registry.scrape()).doesNotContain("CUSTOM-USER-ID", "private-id");
        }
    }
}

