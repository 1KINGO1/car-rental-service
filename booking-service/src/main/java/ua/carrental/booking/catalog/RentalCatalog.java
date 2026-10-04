package ua.carrental.booking.catalog;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import ua.carrental.booking.resilience.ResilientCalls;

@Component
public class RentalCatalog {
    private final CustomerClient customers;
    private final CatalogClient fleet;
    private final ResilientCalls calls;

    public RentalCatalog(CustomerClient customers, CatalogClient fleet, ResilientCalls calls) {
        this.customers = customers;
        this.fleet = fleet;
        this.calls = calls;
    }

    public Vehicle vehicleFor(UUID customerId, UUID vehicleId) {
        try {
            Eligibility customer = calls.execute("customers", () -> customers.getEligibility(customerId), () -> null);
            if (customer == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Customer verification is unavailable");
            }
            if (!customer.allowed()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Customer is not verified");
            }
            Vehicle vehicle = calls.execute("fleet", () -> fleet.getVehicle(vehicleId), () -> null);
            if (vehicle == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Fleet returned an empty response");
            }
            return vehicle;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Customer or vehicle does not exist");
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Catalog service is unavailable");
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Catalog service is unavailable");
        }
    }

    public CatalogResult vehicles(java.util.List<UUID> ids) {
        if (ids.isEmpty()) return new CatalogResult(java.util.List.of(), true);
        return calls.execute("fleet", () -> {
            var vehicles = fleet.getVehicles(ids.stream().distinct().toList());
            if (vehicles == null) throw new org.springframework.web.client.ResourceAccessException("Empty catalog response");
            return new CatalogResult(vehicles, true);
        }, () -> new CatalogResult(java.util.List.of(), false));
    }

    public record CatalogResult(java.util.List<Vehicle> vehicles, boolean available) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Eligibility(UUID customerId, boolean allowed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Vehicle(UUID id, String brand, String model, String plate, BigDecimal dailyRate) {
    }
}
