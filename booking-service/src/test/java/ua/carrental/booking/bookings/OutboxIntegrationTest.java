package ua.carrental.booking.bookings;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ua.carrental.booking.catalog.RentalCatalog;
import ua.carrental.booking.messaging.*;
import ua.carrental.events.BookingCreated;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"DB_URL=jdbc:h2:mem:kafka-booking;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "DB_USER=sa", "DB_PASSWORD=", "messaging.enabled=true", "spring.kafka.admin.auto-create=true",
        "messaging.outbox-initial-delay=3600000", "spring.main.banner-mode=off"})
@EmbeddedKafka(partitions = 3, topics = BookingCreated.TOPIC)
@DirtiesContext
class OutboxIntegrationTest {
    @Autowired BookingService service;
    @Autowired JdbcClient jdbc;
    @Autowired OutboxRelay relay;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean RentalCatalog catalog;
    @MockitoSpyBean OutboxRepository outbox;

    @BeforeEach
    void prepare() {
        jdbc.sql("DELETE FROM outbox_events").update();
        jdbc.sql("DELETE FROM bookings").update();
        when(catalog.vehicleFor(any(), any())).thenAnswer(call -> new RentalCatalog.Vehicle(call.getArgument(1),
                "Toyota", "Corolla", "AA1000BB", new BigDecimal("1200.00")));
    }

    CreateBookingRequest request() {
        return new CreateBookingRequest(UUID.randomUUID(), UUID.randomUUID(), LocalDate.now().plusDays(1), LocalDate.now().plusDays(3));
    }

    @Test
    void createsEntityAndOutboxThenPublishesWithAggregateKey() {
        var booking = service.create(request());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL").query(Integer.class).single()).isEqualTo(1);
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.consumerProps("outbox-" + UUID.randomUUID(), "false", broker));
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<String, String>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(BookingCreated.TOPIC));
            assertThat(relay.publishBatch()).isEqualTo(1);
            ConsumerRecord<String, String> record = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (record == null && System.nanoTime() < deadline) {
                for (var candidate : consumer.poll(Duration.ofMillis(250))) {
                    if (candidate.key().equals(booking.id().toString())) record = candidate;
                }
            }
            assertThat(record).isNotNull();
            assertThat(record.key()).isEqualTo(booking.id().toString());
            assertThat(record.value()).contains(booking.id().toString(), "BookingCreated", "2400.00");
            assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NOT NULL").query(Integer.class).single()).isEqualTo(1);
            assertThat(relay.publishBatch()).isZero();
        }
    }

    @Test
    void outboxFailureRollsBackBookingAndEvent() {
        doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("Simulated outbox write failure");
        }).when(org.springframework.test.util.AopTestUtils.<OutboxRepository>getUltimateTargetObject(outbox)).append(any());
        assertThatThrownBy(() -> service.create(request())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM bookings").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_events").query(Integer.class).single()).isZero();
    }

    @Test
    void failedPublicationLeavesEventPendingForNextAttempt() {
        service.create(request());
        @SuppressWarnings("unchecked")
        org.springframework.kafka.core.KafkaTemplate<String, String> failedKafka = mock(org.springframework.kafka.core.KafkaTemplate.class);
        when(failedKafka.send(anyString(), anyString(), anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Broker unavailable")));
        var failedRelay = new OutboxRelay(jdbc, failedKafka);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> failedRelay.publishBatch()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL").query(Integer.class).single()).isEqualTo(1);
        assertThat(relay.publishBatch()).isEqualTo(1);
    }

    @Test
    void acknowledgementBeforeDatabaseRollbackCanBeSafelyRepublished() {
        var booking = service.create(request());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(relay.publishBatch()).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL").query(Integer.class).single()).isEqualTo(1);
        assertThat(relay.publishBatch()).isEqualTo(1);
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.consumerProps("republication-" + UUID.randomUUID(), "false", broker));
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<String, String>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(BookingCreated.TOPIC));
            List<String> payloads = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (payloads.size() < 2 && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    if (record.key().equals(booking.id().toString())) payloads.add(record.value());
                }
            }
            assertThat(payloads).hasSize(2);
            assertThat(payloads.get(0)).isEqualTo(payloads.get(1));
        }
    }

    @Test
    void outerTransactionRollbackRemovesBothWrites() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.create(request());
            status.setRollbackOnly();
        });
        assertThat(jdbc.sql("SELECT COUNT(*) FROM bookings").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_events").query(Integer.class).single()).isZero();
    }
}
