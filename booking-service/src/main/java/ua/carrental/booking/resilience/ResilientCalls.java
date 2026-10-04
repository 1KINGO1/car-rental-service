package ua.carrental.booking.resilience;

import io.github.resilience4j.bulkhead.*;
import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.*;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class ResilientCalls {
    private final CircuitBreakerRegistry breakers;
    private final RetryRegistry retries;
    private final BulkheadRegistry bulkheads;

    public ResilientCalls(
            @Value("${remote-protection.window-size:10}") int window,
            @Value("${remote-protection.open-duration:10s}") Duration open,
            @Value("${remote-protection.max-attempts:3}") int attempts,
            @Value("${remote-protection.initial-delay:100ms}") Duration delay,
            @Value("${remote-protection.max-concurrent-calls:20}") int concurrency) {
        breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(window).minimumNumberOfCalls(window).failureRateThreshold(50)
                .waitDurationInOpenState(open).permittedNumberOfCallsInHalfOpenState(3)
                .ignoreException(ex -> ex instanceof RestClientResponseException response
                        && !transientFailure(response)).build());
        retries = RetryRegistry.of(RetryConfig.custom().maxAttempts(attempts)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(delay.toMillis(), 2, 0.5))
                .retryOnException(ResilientCalls::transientFailure).build());
        bulkheads = BulkheadRegistry.of(BulkheadConfig.custom().maxConcurrentCalls(concurrency)
                .maxWaitDuration(Duration.ZERO).build());
    }

    public <T> T execute(String name, Supplier<T> action, Supplier<T> fallback) {
        Supplier<T> retried = Retry.decorateSupplier(retries.retry(name), action);
        Supplier<T> guarded = CircuitBreaker.decorateSupplier(breaker(name), retried);
        try {
            return Bulkhead.decorateSupplier(bulkheads.bulkhead(name), guarded).get();
        } catch (CallNotPermittedException | BulkheadFullException | ResourceAccessException exception) {
            return fallback.get();
        } catch (RestClientResponseException exception) {
            if (!transientFailure(exception)) throw exception;
            return fallback.get();
        }
    }

    public CircuitBreaker breaker(String name) {
        return breakers.circuitBreaker(name);
    }

    private static boolean transientFailure(Throwable exception) {
        if (exception instanceof ResourceAccessException) return true;
        if (exception instanceof RestClientResponseException response) {
            int code = response.getStatusCode().value();
            return code == 502 || code == 503 || code == 504 || code == 500;
        }
        return false;
    }
}
