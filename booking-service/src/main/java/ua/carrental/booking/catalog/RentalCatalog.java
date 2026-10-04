package ua.carrental.booking.catalog;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RentalCatalog {
    private final CustomerClient customers;
    private final CatalogClient fleet;

    public RentalCatalog(CustomerClient customers, CatalogClient fleet) {
        this.customers = customers;
        this.fleet = fleet;
    }

    public Vehicle vehicleFor(UUID customerId, UUID vehicleId) {
        try {
            Eligibility customer = customers.getEligibility(customerId);
            if (customer == null || !customer.allowed()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Customer is not verified");
            }
            Vehicle vehicle = fleet.getVehicle(vehicleId);
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Eligibility(UUID customerId, boolean allowed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Vehicle(UUID id, String brand, String model, String plate, BigDecimal dailyRate) {
    }
}
