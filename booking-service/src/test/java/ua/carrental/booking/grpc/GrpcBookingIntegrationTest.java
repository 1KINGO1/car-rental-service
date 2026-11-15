package ua.carrental.booking.grpc;

import com.sun.net.httpserver.HttpServer;
import io.grpc.*;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.*;
import ua.carrental.booking.BookingApplication;
import ua.carrental.grpc.v1.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(20)
class GrpcBookingIntegrationTest {
    static final UUID VEHICLE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID CUSTOMER = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final AtomicInteger calls = new AtomicInteger();
    static ConfigurableApplicationContext context;
    static Server fleet;
    static HttpServer customers;
    static RestClient http;
    @BeforeAll static void start() throws Exception {
        fleet = NettyServerBuilder.forPort(0).addService(new FleetServiceGrpc.FleetServiceImplBase() {
            @Override public void getVehicle(VehicleRequest request, StreamObserver<VehicleReply> out) {
                calls.incrementAndGet();
                if (!request.getVehicleId().equals(VEHICLE.toString())) { out.onError(Status.UNAVAILABLE.asRuntimeException()); return; }
                out.onNext(VehicleReply.newBuilder().setId(VEHICLE.toString()).setBrand("Toyota").setModel("Corolla")
                        .setPlate("BOOK-GRPC").setDailyRate("1200.00").build()); out.onCompleted();
            }
        }).build().start();
        customers = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        customers.createContext("/api/customers/", exchange -> {
            byte[] bytes = ("{\"customerId\":\"" + CUSTOMER + "\",\"allowed\":true}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        }); customers.start();
        context = SpringApplication.run(BookingApplication.class, "--spring.profiles.active=grpc", "--server.port=0",
                "--DB_URL=jdbc:h2:mem:grpc-booking;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "--DB_USER=sa", "--DB_PASSWORD=",
                "--fleet.grpc.target=127.0.0.1:" + fleet.getPort(), "--CUSTOMER_URL=http://127.0.0.1:" + customers.getAddress().getPort(),
                "--messaging.enabled=false", "--spring.kafka.admin.auto-create=false", "--logging.structured.format.console=");
        http = RestClient.create("http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort());
    }
    @AfterAll static void stop() throws Exception {
        if (context != null) context.close();
        if (customers != null) customers.stop(0);
        if (fleet != null) fleet.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
    @Test void realBookingUsesGrpcCatalog() {
        int before = calls.get();
        var result = http.post().uri("/api/bookings").header("Idempotency-Key", "grpc-booking")
                .body(Map.of("customerId", CUSTOMER, "vehicleId", VEHICLE, "startDate", LocalDate.now().plusDays(1),
                        "endDate", LocalDate.now().plusDays(3))).retrieve().body(Map.class);
        assertThat(result).containsEntry("vehiclePlate", "BOOK-GRPC");
        assertThat(new java.math.BigDecimal(result.get("total").toString())).isEqualByComparingTo("2400.00");
        assertThat(calls.get() - before).isEqualTo(1);
        System.out.println("EVIDENCE booking created using gRPC catalog total=2400.00");
    }
    @Test void exhaustedGrpcRetriesDoNotMultiplyWithHttpRetryWrapper() {
        int before = calls.get();
        assertThatThrownBy(() -> http.post().uri("/api/bookings").header("Idempotency-Key", "grpc-unavailable")
                .body(Map.of("customerId", CUSTOMER, "vehicleId", UUID.randomUUID(), "startDate", LocalDate.now().plusDays(1),
                        "endDate", LocalDate.now().plusDays(2))).retrieve().body(Map.class))
                .isInstanceOfSatisfying(RestClientResponseException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
        assertThat(calls.get() - before).isEqualTo(4);
        System.out.println("EVIDENCE booking fleet unavailable HTTP=503 actual-gRPC-attempts=4 (no nested retries)");
    }
    @Test void unaryHttpFacadeReturnsJson() {
        assertThat(http.get().uri("/api/grpc/vehicles/" + VEHICLE).retrieve().body(Map.class)).containsEntry("plate", "BOOK-GRPC");
    }
    @Test void malformedStreamItemsReturn400() {
        assertThatThrownBy(() -> http.post().uri("/api/grpc/quotes/client-stream").body(List.of(Map.of("days", 2)))
                .retrieve().body(Map.class)).isInstanceOfSatisfying(RestClientResponseException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
    }
}
