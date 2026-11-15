package ua.carrental.booking.grpc;

import io.grpc.stub.StreamObserver;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import ua.carrental.grpc.client.FleetGrpcClient;
import ua.carrental.grpc.v1.*;

/** HTTP facade for demonstrations; booking-service performs the actual gRPC calls. */
@RestController
@Profile("grpc")
@RequestMapping("/api/grpc")
public class GrpcDemoController {
    private final FleetGrpcClient client;
    public GrpcDemoController(FleetGrpcClient client) { this.client = client; }
    @GetMapping("/vehicles/{id}")
    Object vehicle(@PathVariable UUID id) {
        try { return FleetGrpcConfiguration.vehicle(client.getVehicle(id.toString())); }
        catch (io.grpc.StatusRuntimeException ex) { throw FleetGrpcConfiguration.toHttp(ex); }
    }
    @GetMapping("/vehicles")
    Object vehicles() {
        try { return client.listVehicles(List.of()).stream().map(FleetGrpcConfiguration::vehicle).toList(); }
        catch (io.grpc.StatusRuntimeException ex) { throw FleetGrpcConfiguration.toHttp(ex); }
    }
    @PostMapping("/quotes/client-stream")
    Object quote(@RequestBody List<Item> items) {
        var summary = await(client.calculateQuote(toProto(items)));
        return Map.of("itemCount", summary.getItemCount(), "total", summary.getTotal(), "currency", summary.getCurrency());
    }
    @PostMapping("/quotes/bidi")
    Object bidi(@RequestBody List<Item> items) {
        var requests = toProto(items);
        var result = new CompletableFuture<List<Map<String, Object>>>();
        var input = client.watchQuotes(new StreamObserver<>() {
            private final List<Map<String, Object>> lines = new ArrayList<>();
            public void onNext(QuoteLine line) {
                lines.add(Map.of("requestId", line.getRequestId(), "vehicleId", line.getVehicleId(),
                        "days", line.getDays(), "amount", line.getAmount(), "currency", line.getCurrency()));
            }
            public void onError(Throwable error) { result.completeExceptionally(error); }
            public void onCompleted() { result.complete(List.copyOf(lines)); }
        });
        requests.forEach(input::onNext); input.onCompleted();
        return await(result);
    }
    private static List<QuoteItem> toProto(List<Item> items) {
        if (items == null || items.size() > 100 || items.stream().anyMatch(i -> i == null || i.vehicleId() == null || i.requestId() == null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most 100 non-null items with requestId and vehicleId");
        return items.stream().map(i -> QuoteItem.newBuilder().setRequestId(i.requestId()).setVehicleId(i.vehicleId().toString()).setDays(i.days()).build()).toList();
    }
    private static <T> T await(CompletableFuture<T> result) {
        try { return result.get(); } // gRPC deadline always terminates the future.
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted"); }
        catch (ExecutionException ex) { throw FleetGrpcConfiguration.toHttp(ex.getCause()); }
    }
    public record Item(String requestId, UUID vehicleId, int days) {}
}
