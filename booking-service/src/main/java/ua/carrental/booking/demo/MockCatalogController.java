package ua.carrental.booking.demo;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@Profile("http-demo")
@RestController
public class MockCatalogController {
    public static final UUID SLOW_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final Logger log = LoggerFactory.getLogger(MockCatalogController.class);

    @GetMapping("/mock/api/vehicles/{id}")
    public Map<String, Object> vehicle(@PathVariable UUID id,
            @RequestHeader("X-Correlation-Id") String correlationId) throws InterruptedException {
        long delay = SLOW_ID.equals(id) ? 5000 : 0;
        log.info("MOCK received id={} X-Correlation-Id={} delayMs={}", id, correlationId, delay);
        Thread.sleep(delay);
        return Map.of("id", id, "brand", "Toyota", "model", "Corolla", "plate", "AA1234BB",
                "dailyRate", new BigDecimal("1200.00"), "futureField", "ignored by Tolerant Reader");
    }
}
