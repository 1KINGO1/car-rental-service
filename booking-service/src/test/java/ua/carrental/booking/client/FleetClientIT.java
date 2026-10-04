package ua.carrental.booking.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import ua.carrental.booking.client.dto.VehicleDto;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "CUSTOMER_URL=http://127.0.0.1:65535",
                "FLEET_URL=http://127.0.0.1:65535"
        }
)
@ActiveProfiles("mock-remote")
class FleetClientIT {

    @LocalServerPort
    int port;

    @Autowired
    JdkClientHttpRequestFactory requestFactory;

    @Autowired
    CorrelationIdInterceptor correlationIdInterceptor;

    FleetClient fleetClient;

    @BeforeEach
    void setUp() {
        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(requestFactory)
                .requestInterceptor(correlationIdInterceptor)
                .build();

        fleetClient = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(FleetClient.class);
    }

    @Test
    void findById_returnsVehicle_andIgnoresUnknownJsonFields() {
        MDC.put(CorrelationIdInterceptor.MDC_KEY, "test-success-correlation");
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");

        VehicleDto vehicle = fleetClient.findById(id);

        assertThat(vehicle.id()).isEqualTo(id);
        assertThat(vehicle.brand()).isEqualTo("Toyota");
        assertThat(vehicle.model()).isEqualTo("Camry");
        assertThat(vehicle.plate()).isEqualTo("AA1234BB");
        assertThat(vehicle.dailyRate()).isEqualByComparingTo(new BigDecimal("45.00"));
        MDC.clear();
    }

    @Test
    void findSlowById_exceedsReadTimeout_andThrowsResourceAccessException() {
        MDC.put(CorrelationIdInterceptor.MDC_KEY, "test-timeout-correlation");
        UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");

        assertThatThrownBy(() -> fleetClient.findSlowById(id))
                .isInstanceOf(ResourceAccessException.class);

        MDC.clear();
    }
}
