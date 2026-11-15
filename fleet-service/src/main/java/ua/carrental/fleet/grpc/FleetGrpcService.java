package ua.carrental.fleet.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import ua.carrental.fleet.Vehicle;
import ua.carrental.fleet.VehicleRepository;
import ua.carrental.grpc.v1.*;

@Service
public class FleetGrpcService extends FleetServiceGrpc.FleetServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(FleetGrpcService.class);
    private final VehicleRepository vehicles;
    public FleetGrpcService(VehicleRepository vehicles) { this.vehicles = vehicles; }

    @Override
    public void getVehicle(VehicleRequest request, StreamObserver<VehicleReply> reply) {
        unary(reply, () -> toReply(find(request.getVehicleId())));
    }

    @Override
    public void listVehicles(ListVehiclesRequest request, StreamObserver<VehicleReply> reply) {
        try {
            int limit = request.getLimit() == 0 ? 100 : request.getLimit();
            if (limit < 1 || limit > 100 || request.getVehicleIdsCount() > 100)
                throw Status.INVALID_ARGUMENT.withDescription("At most 100 vehicles; limit must be 1..100").asRuntimeException();
            List<Vehicle> rows = request.getVehicleIdsCount() == 0 ? vehicles.findPage(limit)
                    : vehicles.findByIds(request.getVehicleIdsList().stream().map(FleetGrpcService::uuid).toList());
            // The finite page is bounded to 100 small messages.
            for (Vehicle row : rows.stream().limit(limit).toList()) {
                if (cancelled(reply)) return;
                reply.onNext(toReply(row));
            }
            reply.onCompleted();
        } catch (RuntimeException ex) { fail(reply, ex); }
    }

    @Override
    public StreamObserver<QuoteItem> calculateQuote(StreamObserver<QuoteSummary> reply) {
        return new StreamObserver<>() {
            private boolean done;
            private int count;
            private BigDecimal total = BigDecimal.ZERO;
            public void onNext(QuoteItem item) {
                if (done || cancelled(reply)) return;
                try {
                    if (++count > 100) throw Status.RESOURCE_EXHAUSTED.withDescription("At most 100 items").asRuntimeException();
                    total = total.add(new BigDecimal(quote(item).getAmount()));
                } catch (RuntimeException ex) { done = true; fail(reply, ex); }
            }
            public void onError(Throwable error) { done = true; }
            public void onCompleted() {
                if (done || cancelled(reply)) return;
                done = true;
                reply.onNext(QuoteSummary.newBuilder().setItemCount(count).setTotal(total.toPlainString()).setCurrency("UAH").build());
                reply.onCompleted();
            }
        };
    }

    @Override
    public StreamObserver<QuoteItem> watchQuotes(StreamObserver<QuoteLine> reply) {
        return new StreamObserver<>() {
            private boolean done;
            private int count;
            public void onNext(QuoteItem item) {
                if (done || cancelled(reply)) return;
                try {
                    if (++count > 100) throw Status.RESOURCE_EXHAUSTED.withDescription("At most 100 items").asRuntimeException();
                    reply.onNext(quote(item)); // Answer before client half-close.
                } catch (RuntimeException ex) { done = true; fail(reply, ex); }
            }
            public void onError(Throwable error) { done = true; }
            public void onCompleted() { if (!done && !cancelled(reply)) { done = true; reply.onCompleted(); } }
        };
    }

    private QuoteLine quote(QuoteItem item) {
        if (item.getDays() < 1 || item.getDays() > 365 || item.getRequestId().isBlank() || item.getRequestId().length() > 64)
            throw Status.INVALID_ARGUMENT.withDescription("days must be 1..365 and request_id 1..64 characters").asRuntimeException();
        Vehicle vehicle = find(item.getVehicleId());
        return QuoteLine.newBuilder().setRequestId(item.getRequestId()).setVehicleId(vehicle.id().toString())
                .setDays(item.getDays()).setCurrency("UAH")
                .setAmount(vehicle.dailyRate().multiply(BigDecimal.valueOf(item.getDays())).toPlainString()).build();
    }
    private Vehicle find(String id) {
        return vehicles.find(uuid(id)).orElseThrow(() -> Status.NOT_FOUND.withDescription("Vehicle not found").asRuntimeException());
    }
    private static UUID uuid(String value) {
        try {
            UUID result = UUID.fromString(value);
            if (!result.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return result;
        } catch (IllegalArgumentException ex) { throw Status.INVALID_ARGUMENT.withDescription("vehicle_id must be a UUID").asRuntimeException(); }
    }
    private static VehicleReply toReply(Vehicle v) {
        return VehicleReply.newBuilder().setId(v.id().toString()).setBrand(v.brand()).setModel(v.model())
                .setPlate(v.plate()).setDailyRate(v.dailyRate().toPlainString()).build();
    }
    private static <T> void unary(StreamObserver<T> reply, Supplier<T> action) {
        try { T value = action.get(); if (!cancelled(reply)) { reply.onNext(value); reply.onCompleted(); } }
        catch (RuntimeException ex) { fail(reply, ex); }
    }
    private static boolean cancelled(StreamObserver<?> reply) {
        return reply instanceof ServerCallStreamObserver<?> server && server.isCancelled();
    }
    private static void fail(StreamObserver<?> reply, RuntimeException error) {
        if (cancelled(reply)) return;
        Status status;
        if (error instanceof StatusRuntimeException grpc) status = grpc.getStatus();
        else if (error instanceof TransientDataAccessException) status = Status.UNAVAILABLE.withDescription("Fleet database temporarily unavailable");
        else { log.error("Fleet gRPC operation failed", error); status = Status.INTERNAL.withDescription("Fleet operation failed"); }
        reply.onError(status.asRuntimeException());
    }
}
