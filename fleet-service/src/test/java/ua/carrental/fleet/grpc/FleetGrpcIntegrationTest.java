package ua.carrental.fleet.grpc;

import io.grpc.*;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import ua.carrental.fleet.*;
import ua.carrental.grpc.client.*;
import ua.carrental.grpc.v1.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(25)
class FleetGrpcIntegrationTest {
    static ConfigurableApplicationContext context;
    static FleetGrpcService service;
    static Vehicle car;
    static Vehicle second;
    static ManagedChannel springChannel;
    static FleetGrpcClient client;

    @BeforeAll static void start() {
        context = SpringApplication.run(FleetApplication.class, "--server.port=0", "--spring.grpc.server.port=0",
                "--DB_URL=jdbc:h2:mem:grpc-fleet;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "--DB_USER=sa", "--DB_PASSWORD=",
                "--spring.main.banner-mode=off", "--logging.structured.format.console=");
        var repository = context.getBean(VehicleRepository.class);
        car = repository.create(new CreateVehicleRequest("Toyota", "Corolla", "GRPC100", new BigDecimal("1200.00")));
        second = repository.create(new CreateVehicleRequest("Skoda", "Octavia", "GRPC200", new BigDecimal("1500.00")));
        service = context.getBean(FleetGrpcService.class);
        int port = context.getBean(GrpcServerLifecycle.class).getPort();
        springChannel = FleetChannels.open("localhost:" + port, true);
        client = new FleetGrpcClient(springChannel, 5000);
        System.out.println("EVIDENCE Spring Boot=" + org.springframework.boot.SpringBootVersion.getVersion()
                + " Java=" + System.getProperty("java.version") + " transport=TCP port=" + port);
    }
    @AfterAll static void stop() throws Exception {
        if (springChannel != null) springChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        if (context != null) context.close();
    }
    static QuoteItem item(String id, Vehicle v, int days) {
        return QuoteItem.newBuilder().setRequestId(id).setVehicleId(v.id().toString()).setDays(days).build();
    }
    @Test void unaryReadsExistingRepository() {
        var result = client.getVehicle(car.id().toString());
        assertThat(result.getPlate()).isEqualTo("GRPC100");
        assertThat(result.getDailyRate()).isEqualTo("1200.00");
        System.out.println("EVIDENCE unary plate=" + result.getPlate() + " rate=" + result.getDailyRate());
    }
    @Test void serverStreamingReturnsDistinctExistingVehicles() {
        var result = client.listVehicles(List.of(car.id().toString(), second.id().toString(), car.id().toString(), UUID.randomUUID().toString()));
        assertThat(result).extracting(VehicleReply::getPlate).containsExactlyInAnyOrder("GRPC100", "GRPC200");
        assertThat(client.listVehicles(List.of())).hasSize(2);
        System.out.println("EVIDENCE server-streaming messages=" + result.size());
    }
    @Test void clientStreamingAggregatesExactDecimalValues() throws Exception {
        var result = client.calculateQuote(List.of(item("a", car, 2), item("b", second, 3))).get(6, TimeUnit.SECONDS);
        assertThat(result.getItemCount()).isEqualTo(2);
        assertThat(result.getTotal()).isEqualTo("6900.00");
        System.out.println("EVIDENCE client-streaming count=2 total=" + result.getTotal() + " UAH");
    }
    @Test void bidiRespondsBeforeClientHalfClose() throws Exception {
        var output = new Probe<QuoteLine>();
        var input = client.watchQuotes(output);
        input.onNext(item("first", car, 2));
        var first = output.messages.poll(3, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(first.getRequestId()).isEqualTo("first");
        assertThat(first.getAmount()).isEqualTo("2400.00");
        assertThat(output.completed.isDone()).isFalse();
        input.onNext(item("second", second, 3));
        assertThat(output.messages.poll(3, TimeUnit.SECONDS).getAmount()).isEqualTo("4500.00");
        input.onCompleted(); output.completed.get(3, TimeUnit.SECONDS);
        System.out.println("EVIDENCE bidi first response received BEFORE half-close; amounts=2400.00,4500.00");
    }
    @Test void emptyStreamsComplete() throws Exception {
        assertThat(client.calculateQuote(List.of()).get(6, TimeUnit.SECONDS).getItemCount()).isZero();
        var output = new Probe<QuoteLine>(); client.watchQuotes(output).onCompleted();
        output.completed.get(3, TimeUnit.SECONDS); assertThat(output.messages).isEmpty();
    }
    @Test void validationAndMissingVehicleHavePreciseStatus() {
        assertStatus(() -> client.getVehicle("bad-id"), Status.Code.INVALID_ARGUMENT);
        assertStatus(() -> client.getVehicle(UUID.randomUUID().toString()), Status.Code.NOT_FOUND);
        assertStatus(() -> client.calculateQuote(List.of(item("bad", car, 0))).join(), Status.Code.INVALID_ARGUMENT);
        assertStatus(() -> client.calculateQuote(List.of(item("bad", car, 366))).join(), Status.Code.INVALID_ARGUMENT);
    }
    @Test void streamLimitAndInvalidListAreRejected() throws Exception {
        var output = new Probe<QuoteSummary>();
        var input = FleetServiceGrpc.newStub(springChannel).withDeadlineAfter(5, TimeUnit.SECONDS).calculateQuote(output);
        for (int i = 0; i < 101; i++) input.onNext(item("item" + i, car, 1));
        input.onCompleted();
        assertStatus(() -> output.completed.join(), Status.Code.RESOURCE_EXHAUSTED);
        assertStatus(() -> FleetServiceGrpc.newBlockingStub(springChannel).withDeadlineAfter(5, TimeUnit.SECONDS)
                .listVehicles(ListVehiclesRequest.newBuilder().setLimit(101).build()).hasNext(), Status.Code.INVALID_ARGUMENT);
    }
    @ParameterizedTest @ValueSource(strings = {"GetVehicle", "ListVehicles", "CalculateQuote", "WatchQuotes"})
    void retriesAllFourMethodsBeforeHeaders(String method) throws Exception {
        var attempts = new Attempts(2, Status.UNAVAILABLE);
        try (var harness = new Harness(service, attempts)) {
            var c = new FleetGrpcClient(harness.channel, 5000);
            switch (method) {
                case "GetVehicle" -> assertThat(c.getVehicle(car.id().toString()).getPlate()).isEqualTo("GRPC100");
                case "ListVehicles" -> assertThat(c.listVehicles(List.of())).hasSize(2);
                case "CalculateQuote" -> assertThat(c.calculateQuote(List.of(item("a", car, 2))).get(6, TimeUnit.SECONDS).getTotal()).isEqualTo("2400.00");
                case "WatchQuotes" -> {
                    var output = new Probe<QuoteLine>(); var input = c.watchQuotes(output);
                    input.onNext(item("a", car, 2)); input.onCompleted();
                    output.completed.get(6, TimeUnit.SECONDS);
                    assertThat(output.messages).hasSize(1);
                }
            }
            assertThat(attempts.count.get()).isEqualTo(3);
            assertThat(attempts.previous).containsExactly("0", "1", "2");
            assertThat(attempts.times.get(1) - attempts.times.get(0)).isGreaterThan(TimeUnit.MILLISECONDS.toNanos(50));
            System.out.println("EVIDENCE retry " + method + " attempts=3 previous=[0,1,2] result=OK");
        }
    }
    @Test void retryStopsAtFourAttempts() throws Exception {
        var attempts = new Attempts(Integer.MAX_VALUE, Status.UNAVAILABLE);
        try (var harness = new Harness(service, attempts)) {
            assertStatus(() -> new FleetGrpcClient(harness.channel, 5000).getVehicle(car.id().toString()), Status.Code.UNAVAILABLE);
            assertThat(attempts.count.get()).isEqualTo(4);
            assertThat(attempts.previous).containsExactly("0", "1", "2", "3");
            System.out.println("EVIDENCE retry exhausted attempts=4 final=UNAVAILABLE");
        }
    }
    @ParameterizedTest @ValueSource(strings = {"INVALID_ARGUMENT", "NOT_FOUND", "INTERNAL"})
    void nonRetryableStatusesAreNotRepeated(String code) throws Exception {
        var attempts = new Attempts(10, Status.fromCode(Status.Code.valueOf(code)));
        try (var harness = new Harness(service, attempts)) {
            assertStatus(() -> new FleetGrpcClient(harness.channel, 5000).getVehicle(car.id().toString()), Status.Code.valueOf(code));
            assertThat(attempts.count.get()).isEqualTo(1);
            System.out.println("EVIDENCE non-retryable status=" + code + " attempts=1");
        }
    }
    @Test void deadlineBoundsTheWholeRetrySequence() throws Exception {
        var attempts = new Attempts(100, Status.UNAVAILABLE);
        try (var harness = new Harness(service, attempts)) {
            long started = System.nanoTime();
            assertStatus(() -> new FleetGrpcClient(harness.channel, 100).getVehicle(car.id().toString()), Status.Code.DEADLINE_EXCEEDED);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1500);
            assertThat(attempts.count.get()).isLessThan(4);
            System.out.println("EVIDENCE deadline=100ms final=DEADLINE_EXCEEDED attempts=" + attempts.count.get());
        }
    }
    @Test void committedServerStreamIsNotReplayed() throws Exception {
        var count = new AtomicInteger();
        var partial = new FleetServiceGrpc.FleetServiceImplBase() {
            @Override public void listVehicles(ListVehiclesRequest request, StreamObserver<VehicleReply> out) {
                count.incrementAndGet(); out.onNext(VehicleReply.newBuilder().setId(car.id().toString()).build());
                out.onError(Status.UNAVAILABLE.withDescription("Failure after response headers").asRuntimeException());
            }
        };
        try (var harness = new Harness(partial, new Attempts(0, Status.UNAVAILABLE))) {
            var output = new Probe<VehicleReply>();
            FleetServiceGrpc.newStub(harness.channel).withDeadlineAfter(5, TimeUnit.SECONDS)
                    .listVehicles(ListVehiclesRequest.getDefaultInstance(), output);
            assertStatus(() -> output.completed.join(), Status.Code.UNAVAILABLE);
            assertThat(output.messages).hasSize(1); assertThat(count.get()).isEqualTo(1);
            System.out.println("EVIDENCE committed server-stream messages=1 attempts=1 final=UNAVAILABLE (no replay)");
        }
    }
    @Test void committedBidiIsNotReplayed() throws Exception {
        var attempts = new Attempts(0, Status.UNAVAILABLE);
        try (var harness = new Harness(service, attempts)) {
            var output = new Probe<QuoteLine>();
            var input = new FleetGrpcClient(harness.channel, 5000).watchQuotes(output);
            input.onNext(item("first", car, 1));
            assertThat(output.messages.poll(3, TimeUnit.SECONDS)).isNotNull();
            harness.server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
            assertThatThrownBy(() -> output.completed.join()).satisfies(error ->
                    assertThat(Status.fromThrowable(error).getCode()).isIn(Status.Code.UNAVAILABLE, Status.Code.CANCELLED));
            assertThat(attempts.count.get()).isEqualTo(1);
            System.out.println("EVIDENCE committed bidi server stopped; attempts=1 transport closed (no replay)");
        }
    }
    @Test void unavailableTcpPortRecoversDuringTheSameCall() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var failures = new CountDownLatch(1);
        var traces = new AtomicInteger();
        var tracer = new ClientStreamTracer.Factory() {
            @Override public ClientStreamTracer newClientStreamTracer(ClientStreamTracer.StreamInfo info, Metadata headers) {
                int n = traces.incrementAndGet();
                System.out.println("EVIDENCE unavailable-port client-attempt=" + n + " previous=" + info.getPreviousAttempts());
                return new ClientStreamTracer() {
                    @Override public void streamClosed(Status status) {
                        if (status.getCode() == Status.Code.UNAVAILABLE) failures.countDown();
                    }
                };
            }
        };
        ManagedChannel channel = FleetChannels.open("127.0.0.1:" + port, true);
        Server server = null;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> FleetServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(8, TimeUnit.SECONDS).withOption(CallOptions.Key.create("evidence"), true)
                    .withInterceptors(new ClientInterceptor() {
                        public <Q, A> ClientCall<Q, A> interceptCall(io.grpc.MethodDescriptor<Q, A> m, CallOptions o, Channel next) {
                            return next.newCall(m, o.withStreamTracerFactory(tracer));
                        }
                    }).getVehicle(VehicleRequest.newBuilder().setVehicleId(car.id().toString()).build()));
            assertThat(failures.await(3, TimeUnit.SECONDS)).isTrue();
            server = NettyServerBuilder.forPort(port).addService(service).build().start();
            assertThat(future.get(9, TimeUnit.SECONDS).getPlate()).isEqualTo("GRPC100");
            assertThat(traces.get()).isBetween(2, 4);
            System.out.println("EVIDENCE TCP recovery same logical call attempts=" + traces.get() + " result=OK");
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
    static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, Status.Code code) {
        assertThatThrownBy(action).satisfies(error -> assertThat(Status.fromThrowable(error).getCode()).isEqualTo(code));
    }
    static class Probe<T> implements StreamObserver<T> {
        final BlockingQueue<T> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Void> completed = new CompletableFuture<>();
        public void onNext(T value) { messages.add(value); }
        public void onError(Throwable error) { completed.completeExceptionally(error); }
        public void onCompleted() { completed.complete(null); }
    }
    static class Attempts implements ServerInterceptor {
        final AtomicInteger count = new AtomicInteger();
        final List<String> previous = new CopyOnWriteArrayList<>();
        final List<Long> times = new CopyOnWriteArrayList<>();
        final int failures; final Status failure;
        Attempts(int failures, Status failure) { this.failures = failures; this.failure = failure; }
        public <Q, A> ServerCall.Listener<Q> interceptCall(ServerCall<Q, A> call, Metadata headers, ServerCallHandler<Q, A> next) {
            String p = headers.get(Metadata.Key.of("grpc-previous-rpc-attempts", Metadata.ASCII_STRING_MARSHALLER));
            previous.add(p == null ? "0" : p); times.add(System.nanoTime()); int n = count.incrementAndGet();
            System.out.println("EVIDENCE method=" + call.getMethodDescriptor().getBareMethodName() + " attempt=" + n
                    + " previous=" + previous.getLast() + " injectedStatus=" + (n <= failures ? failure.getCode() : "NONE"));
            if (n <= failures) { call.close(failure.withDescription("Test-only temporary failure"), new Metadata()); return new ServerCall.Listener<>() {}; }
            return next.startCall(call, headers);
        }
    }
    static class Harness implements AutoCloseable {
        final Server server; final ManagedChannel channel;
        Harness(BindableService service, ServerInterceptor interceptor) throws Exception {
            server = NettyServerBuilder.forPort(0).addService(ServerInterceptors.intercept(service, interceptor)).build().start();
            channel = FleetChannels.open("localhost:" + server.getPort(), true);
        }
        public void close() throws Exception {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
