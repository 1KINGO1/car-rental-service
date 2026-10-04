package ua.carrental.booking.client;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import ua.carrental.booking.client.dto.VehicleDto;

@Component
@Profile("http-client-demo")
public class HttpClientDemoRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HttpClientDemoRunner.class);

    private final FleetClient fleetClient;

    public HttpClientDemoRunner(FleetClient fleetClient) {
        this.fleetClient = fleetClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID vehicleId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        MDC.put(CorrelationIdInterceptor.MDC_KEY, "demo-correlation-" + UUID.randomUUID());

        log.info("=== HTTP client demo: successful @GetExchange ===");
        VehicleDto vehicle = fleetClient.findById(vehicleId);
        log.info("OK vehicle brand={} model={} plate={} dailyRate={}",
                vehicle.brand(), vehicle.model(), vehicle.plate(), vehicle.dailyRate());

        log.info("=== HTTP client demo: read timeout (>3s) ===");
        try {
            fleetClient.findSlowById(vehicleId);
            log.error("UNEXPECTED: slow call completed without timeout");
        } catch (ResourceAccessException ex) {
            log.info("TIMEOUT confirmed: {} — {}", ex.getClass().getName(), ex.getMessage());
        } finally {
            MDC.remove(CorrelationIdInterceptor.MDC_KEY);
        }
    }
}
