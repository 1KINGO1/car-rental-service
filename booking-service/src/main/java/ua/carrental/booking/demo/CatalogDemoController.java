package ua.carrental.booking.demo;

import java.util.UUID;
import org.slf4j.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.ResourceAccessException;
import ua.carrental.booking.catalog.CatalogClient;
import ua.carrental.booking.web.CorrelationIdInterceptor;

@Profile("http-demo")
@RestController
public class CatalogDemoController {
    private static final Logger log = LoggerFactory.getLogger(CatalogDemoController.class);
    private final CatalogClient client;
    public CatalogDemoController(CatalogClient client) { this.client = client; }

    @GetMapping("/demo/vehicles/{id}")
    public ResponseEntity<?> vehicle(@PathVariable UUID id) {
        long started = System.nanoTime();
        try {
            var vehicle = client.getVehicle(id);
            log.info("SUCCESS X-Correlation-Id={} elapsedMs={} dto={}",
                    MDC.get(CorrelationIdInterceptor.HEADER), elapsed(started), vehicle);
            return ResponseEntity.ok(vehicle);
        } catch (ResourceAccessException exception) {
            log.error("TIMEOUT X-Correlation-Id={} elapsedMs={} exception={} cause={}",
                    MDC.get(CorrelationIdInterceptor.HEADER), elapsed(started),
                    exception.getClass().getSimpleName(), exception.getCause(), exception);
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT,
                    "Catalog request failed: " + exception.getClass().getSimpleName());
            problem.setTitle("Catalog unavailable");
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(problem);
        }
    }
    private long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
}
