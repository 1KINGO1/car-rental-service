package ua.carrental.booking.grpc;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ua.carrental.booking.catalog.*;
import ua.carrental.grpc.client.*;
import ua.carrental.grpc.v1.VehicleReply;

@Configuration(proxyBeanMethods = false)
@Profile("grpc")
public class FleetGrpcConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    ManagedChannel fleetGrpcChannel(@Value("${fleet.grpc.target:localhost:9090}") String target,
            @Value("${fleet.grpc.plaintext:true}") boolean plaintext) {
        return FleetChannels.open(target, plaintext);
    }
    @Bean
    FleetGrpcClient fleetGrpcClient(ManagedChannel fleetGrpcChannel,
            @Value("${fleet.grpc.deadline-ms:5000}") long deadline) {
        return new FleetGrpcClient(fleetGrpcChannel, deadline);
    }
    @Bean
    CatalogClient grpcCatalogClient(FleetGrpcClient client) {
        return new CatalogClient() {
            @Override public RentalCatalog.Vehicle getVehicle(UUID id) {
                try { return vehicle(client.getVehicle(id.toString())); }
                catch (StatusRuntimeException ex) { throw toHttp(ex); }
            }
            @Override public List<RentalCatalog.Vehicle> getVehicles(List<UUID> ids) {
                if (ids.isEmpty()) return List.of();
                try { return client.listVehicles(ids.stream().map(UUID::toString).toList()).stream().map(FleetGrpcConfiguration::vehicle).toList(); }
                catch (StatusRuntimeException ex) { throw toHttp(ex); }
            }
        };
    }
    static RentalCatalog.Vehicle vehicle(VehicleReply v) {
        return new RentalCatalog.Vehicle(UUID.fromString(v.getId()), v.getBrand(), v.getModel(), v.getPlate(), new BigDecimal(v.getDailyRate()));
    }
    public static ResponseStatusException toHttp(Throwable error) {
        var status = io.grpc.Status.fromThrowable(error);
        HttpStatus http = switch (status.getCode()) {
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.UNPROCESSABLE_ENTITY;
            case RESOURCE_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS;
            case DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT;
            case UNAVAILABLE, CANCELLED -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
        // ResponseStatusException is not retried by the pre-existing HTTP retry wrapper.
        return new ResponseStatusException(http, "Fleet gRPC: " + status.getCode());
    }
}
