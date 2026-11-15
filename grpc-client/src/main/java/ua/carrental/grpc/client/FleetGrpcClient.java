package ua.carrental.grpc.client;

import io.grpc.Channel;
import io.grpc.stub.StreamObserver;
import java.util.*;
import java.util.concurrent.*;
import ua.carrental.grpc.v1.*;

/** A new deadline is assigned for each logical call, including all retries. */
public final class FleetGrpcClient {
    private final Channel channel;
    private final long deadlineMillis;
    public FleetGrpcClient(Channel channel, long deadlineMillis) {
        if (deadlineMillis <= 0) throw new IllegalArgumentException("Deadline must be positive");
        this.channel = channel; this.deadlineMillis = deadlineMillis;
    }
    private FleetServiceGrpc.FleetServiceBlockingStub blocking() {
        return FleetServiceGrpc.newBlockingStub(channel).withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    }
    private FleetServiceGrpc.FleetServiceStub async() {
        return FleetServiceGrpc.newStub(channel).withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    }
    public VehicleReply getVehicle(String id) {
        return blocking().getVehicle(VehicleRequest.newBuilder().setVehicleId(id).build());
    }
    public List<VehicleReply> listVehicles(List<String> ids) {
        if (ids.size() > 100) throw new IllegalArgumentException("At most 100 vehicle IDs");
        var result = new ArrayList<VehicleReply>();
        blocking().listVehicles(ListVehiclesRequest.newBuilder().addAllVehicleIds(ids).setLimit(100).build())
                .forEachRemaining(result::add);
        return List.copyOf(result);
    }
    public CompletableFuture<QuoteSummary> calculateQuote(List<QuoteItem> items) {
        if (items.size() > 100) throw new IllegalArgumentException("At most 100 quote items");
        var result = new CompletableFuture<QuoteSummary>();
        var input = async().calculateQuote(new StreamObserver<>() {
            private QuoteSummary summary;
            public void onNext(QuoteSummary value) { summary = value; }
            public void onError(Throwable error) { result.completeExceptionally(error); }
            public void onCompleted() {
                if (summary == null) result.completeExceptionally(new IllegalStateException("Empty quote response"));
                else result.complete(summary);
            }
        });
        items.forEach(input::onNext); input.onCompleted();
        return result;
    }
    /** Caller can send and receive independently; it must complete or cancel the stream. */
    public StreamObserver<QuoteItem> watchQuotes(StreamObserver<QuoteLine> replies) {
        return async().watchQuotes(replies);
    }
}
