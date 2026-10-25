package ua.carrental.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/** Five Micrometer types measure actual synchronous /api requests in every service. */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class ApiMetricsFilter extends OncePerRequestFilter {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "OTHER");
    private static final String[] OUTCOMES = {"success", "redirect", "client_error", "server_error"};
    private final MeterRegistry registry;
    // The bean retains this strong reference for the full lifetime of the Gauge.
    private final AtomicInteger inFlight = new AtomicInteger();
    private final LongTaskTimer activeRequests;
    private final DistributionSummary requestBodies;
    private final Map<String, Counter> counters = new HashMap<>();
    private final Map<String, Timer> timers = new HashMap<>();

    public ApiMetricsFilter(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("car.rental.http.in.flight.requests", inFlight, AtomicInteger::get)
                .description("Current synchronous API requests, excluding actuator scrapes")
                .register(registry);
        activeRequests = LongTaskTimer.builder("car.rental.http.active.requests")
                .description("Elapsed time of API requests still running at scrape time")
                .register(registry);
        requestBodies = DistributionSummary.builder("car.rental.http.request.body")
                .baseUnit("bytes")
                .description("Declared Content-Length of completed API requests; unknown lengths excluded")
                .minimumExpectedValue(1.0).maximumExpectedValue(65536.0)
                .publishPercentileHistogram()
                .serviceLevelObjectives(128, 512, 1024, 4096, 16384, 65536)
                .register(registry);
        for (String outcome : OUTCOMES) {
            timers.put(outcome, Timer.builder("car.rental.http.request.duration")
                    .description("Completed synchronous API request duration")
                    .tag("outcome", outcome)
                    .minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofSeconds(120))
                    .publishPercentileHistogram()
                    .serviceLevelObjectives(Duration.ofMillis(100), Duration.ofMillis(500),
                            Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(120))
                    .register(registry));
            for (String method : METHODS) {
                counters.put(method + ":" + outcome, Counter.builder("car.rental.http.requests")
                        .description("Completed API requests by bounded method and response outcome")
                        .tags("method", method, "outcome", outcome).register(registry));
            }
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Timer.Sample elapsed = Timer.start(registry);
        LongTaskTimer.Sample active = activeRequests.start();
        inFlight.incrementAndGet();
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        } finally {
            org.slf4j.LoggerFactory.getLogger(ApiMetricsFilter.class).info("HTTP {} {} status={}", request.getMethod(), request.getRequestURI(), response.getStatus());
            active.stop();
            inFlight.decrementAndGet();
            String outcome = failed ? "server_error" : outcome(response.getStatus());
            String method = METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
            counters.get(method + ":" + outcome).increment();
            elapsed.stop(timers.get(outcome));
            long declaredBytes = request.getContentLengthLong();
            if (declaredBytes >= 0) requestBodies.record(declaredBytes);
        }
    }

    private static String outcome(int status) {
        if (status >= 500) return "server_error";
        if (status >= 400) return "client_error";
        if (status >= 300) return "redirect";
        return "success";
    }
}
