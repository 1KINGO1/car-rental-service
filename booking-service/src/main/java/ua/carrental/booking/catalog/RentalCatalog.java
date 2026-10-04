package ua.carrental.booking;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RentalCatalog {
    private final RestClient customers;
    private final RestClient fleet;

    public RentalCatalog(@Value("${CUSTOMER_URL}") String customerUrl,
                         @Value("${FLEET_URL}") String fleetUrl) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        customers = RestClient.builder().baseUrl(customerUrl).requestFactory(factory).build();
        fleet = RestClient.builder().baseUrl(fleetUrl).requestFactory(factory).build();
    }

    public Vehicle vehicleFor(UUID customerId, UUID vehicleId) {
        try {
            Eligibility customer = customers.get().uri("/api/customers/{id}/eligibility", customerId)
                    .retrieve().body(Eligibility.class);
            if (customer == null || !customer.allowed()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Customer is not verified");
            }
            Vehicle vehicle = fleet.get().uri("/api/vehicles/{id}", vehicleId)
                    .retrieve().body(Vehicle.class);
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

    public record Eligibility(UUID customerId, boolean allowed) {
    }

    public record Vehicle(UUID id, String brand, String model, String plate, BigDecimal dailyRate) {
    }
}
