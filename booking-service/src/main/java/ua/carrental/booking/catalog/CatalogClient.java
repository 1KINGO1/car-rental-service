package ua.carrental.booking.catalog;

import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange(url = "/api/vehicles", accept = "application/json")
public interface CatalogClient {
    @PostExchange("/batch")
    java.util.List<RentalCatalog.Vehicle> getVehicles(@RequestBody java.util.List<UUID> ids);

    @GetExchange("/{id}")
    RentalCatalog.Vehicle getVehicle(@PathVariable("id") UUID id);
}
