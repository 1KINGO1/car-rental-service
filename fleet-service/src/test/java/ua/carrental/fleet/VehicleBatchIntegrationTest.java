package ua.carrental.fleet;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.*;
import static org.assertj.core.api.Assertions.*;

class VehicleBatchIntegrationTest {
    static ConfigurableApplicationContext context;
    static RestClient api;
    static Vehicle first;
    static Vehicle second;

    @BeforeAll
    static void start() {
        context = SpringApplication.run(FleetApplication.class, "--server.port=0",
                "--DB_URL=jdbc:h2:mem:fleet;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "--DB_USER=sa", "--DB_PASSWORD=");
        api = RestClient.create("http://localhost:" + ((WebServerApplicationContext) context).getWebServer().getPort());
        var repository = context.getBean(VehicleRepository.class);
        first = repository.create(new CreateVehicleRequest("Toyota", "Corolla", "AA1000BB", new BigDecimal("1200.00")));
        second = repository.create(new CreateVehicleRequest("Skoda", "Octavia", "AA2000BB", new BigDecimal("1500.00")));
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
    }

    @Test
    void batchReturnsExistingVehiclesWithoutDuplicates() {
        var result = api.post().uri("/api/vehicles/batch")
                .body(List.of(first.id(), second.id(), first.id(), UUID.randomUUID()))
                .retrieve().body(Vehicle[].class);
        assertThat(result).containsExactlyInAnyOrder(first, second);
        assertThat(api.post().uri("/api/vehicles/batch").body(List.of()).retrieve().body(Vehicle[].class)).isEmpty();
    }

    @Test
    void oversizedAndNullIdsReturnProblemDetail() {
        for (var body : List.of(Collections.nCopies(101, first.id()), Arrays.asList(first.id(), null))) {
            assertThatThrownBy(() -> api.post().uri("/api/vehicles/batch").body(body).retrieve().toBodilessEntity())
                    .isInstanceOfSatisfying(RestClientResponseException.class, ex -> {
                        assertThat(ex.getStatusCode().value()).isEqualTo(400);
                        assertThat(ex.getResponseHeaders().getContentType().toString()).isEqualTo("application/problem+json");
                        assertThat(ex.getResponseBodyAs(ProblemDetail.class).getInstance().toString()).isEqualTo("/api/vehicles/batch");
                    });
        }
    }

    @Test
    void missingVehicleReturnsProblemDetail() {
        assertThatThrownBy(() -> api.get().uri("/api/vehicles/" + UUID.randomUUID()).retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(RestClientResponseException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(404);
                    assertThat(ex.getResponseHeaders().getContentType().toString()).isEqualTo("application/problem+json");
                    assertThat(ex.getResponseBodyAs(ProblemDetail.class).getDetail()).isEqualTo("Vehicle not found");
                });
    }
}
