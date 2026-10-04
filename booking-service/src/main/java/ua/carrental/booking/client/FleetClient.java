package ua.carrental.booking.client;

import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import ua.carrental.booking.client.dto.VehicleDto;

@HttpExchange("/api/vehicles")
public interface FleetClient {
    @GetExchange("/{id}")
    VehicleDto findById(@PathVariable UUID id);

    @GetExchange("/slow/{id}")
    VehicleDto findSlowById(@PathVariable UUID id);
}
