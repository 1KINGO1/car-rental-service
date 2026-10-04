package ua.carrental.booking.mock;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.carrental.booking.client.CorrelationIdInterceptor;

@RestController
@Profile("mock-remote")
@RequestMapping("/api")
public class MockRemoteController {

    private static final Logger log = LoggerFactory.getLogger(MockRemoteController.class);

    @GetMapping("/vehicles/{id}")
    public Map<String, Object> vehicle(
            @PathVariable UUID id,
            @RequestHeader(value = CorrelationIdInterceptor.HEADER, required = false) String correlationId
    ) {
        log.info("Mock fleet hit vehicle={} correlationId={}", id, correlationId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("brand", "Toyota");
        body.put("model", "Camry");
        body.put("plate", "AA1234BB");
        body.put("dailyRate", new BigDecimal("45.00"));
        body.put("legacyWarehouseCode", "WH-42");
        return body;
    }

    @GetMapping("/vehicles/slow/{id}")
    public Map<String, Object> slowVehicle(
            @PathVariable UUID id,
            @RequestHeader(value = CorrelationIdInterceptor.HEADER, required = false) String correlationId
    ) throws InterruptedException {
        log.info("Mock fleet slow endpoint vehicle={} correlationId={} (sleep 5s)", id, correlationId);
        Thread.sleep(5_000);
        return vehicle(id, correlationId);
    }

    @GetMapping("/customers/{id}/eligibility")
    public Map<String, Object> eligibility(
            @PathVariable UUID id,
            @RequestHeader(value = CorrelationIdInterceptor.HEADER, required = false) String correlationId
    ) {
        log.info("Mock customer eligibility customer={} correlationId={}", id, correlationId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", id);
        body.put("allowed", true);
        body.put("kycVendorScore", 0.99);
        return body;
    }
}
