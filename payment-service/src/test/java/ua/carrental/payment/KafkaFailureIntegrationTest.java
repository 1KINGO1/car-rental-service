package ua.carrental.payment;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;
import ua.carrental.events.BookingCreated;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"DB_URL=jdbc:h2:mem:kafka-payment;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "DB_USER=sa", "DB_PASSWORD=", "messaging.enabled=true", "spring.kafka.admin.auto-create=true",
        "spring.main.banner-mode=off"})
@EmbeddedKafka(partitions = 3, topics = BookingCreated.TOPIC)
@DirtiesContext
class KafkaFailureIntegrationTest {
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JdbcClient jdbc;
    @Autowired org.springframework.kafka.test.EmbeddedKafkaBroker broker;
    @MockitoSpyBean BookingPaymentHandler handler;
    @MockitoSpyBean BookingEventListener listener;
    final JsonMapper mapper = JsonMapper.builder().build();

    BookingCreated event() {
        return new BookingCreated(UUID.randomUUID(), 1, "BookingCreated", Instant.now(), UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("2400.00"), "UAH");
    }

    void send(BookingCreated event) throws Exception {
        kafka.send(BookingCreated.TOPIC, event.bookingId().toString(), mapper.writeValueAsString(event)).get();
    }

    int payments(BookingCreated event) {
        return jdbc.sql("SELECT COUNT(*) FROM payments WHERE booking_id = ?").param(event.bookingId()).query(Integer.class).single();
    }

    int processed(BookingCreated event) {
        return jdbc.sql("SELECT COUNT(*) FROM processed_events WHERE event_id = ?").param(event.eventId()).query(Integer.class).single();
    }

    @Test
    void permanentFailureRollsBackBusinessEffectAndReachesDltAfterFourAttempts() throws Exception {
        var event = event();
        var attempts = new AtomicInteger();
        doAnswer(call -> {
            call.callRealMethod();
            attempts.incrementAndGet();
            throw new IllegalStateException("Simulated failure after business write");
        }).when(org.springframework.test.util.AopTestUtils.<BookingPaymentHandler>getUltimateTargetObject(handler)).handle(event);
        send(event);
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            assertThat(jdbc.sql("SELECT COUNT(*) FROM failed_events WHERE payload LIKE ?")
                    .param("%" + event.eventId() + "%").query(Integer.class).single()).isEqualTo(1);
        });
        assertThat(attempts.get()).isEqualTo(4);
        assertThat(payments(event)).isZero();
        assertThat(processed(event)).isZero();
        assertThat(jdbc.sql("SELECT topic FROM failed_events WHERE payload LIKE ?").param("%" + event.eventId() + "%")
                .query(String.class).single()).isEqualTo(BookingCreated.TOPIC + "-dlt");
        doCallRealMethod().when(org.springframework.test.util.AopTestUtils.<BookingPaymentHandler>getUltimateTargetObject(handler)).handle(event);
        send(event);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(payments(event)).isEqualTo(1));
        assertThat(processed(event)).isEqualTo(1);
    }

    @Test
    void transientFailureRollsBackThenSucceedsWithoutDuplicates() throws Exception {
        var event = event();
        var attempts = new AtomicInteger();
        doAnswer(call -> {
            var result = call.callRealMethod();
            if (attempts.incrementAndGet() <= 2) throw new IllegalStateException("Simulated temporary failure");
            return result;
        }).when(org.springframework.test.util.AopTestUtils.<BookingPaymentHandler>getUltimateTargetObject(handler)).handle(event);
        send(event);
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> assertThat(payments(event)).isEqualTo(1));
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(processed(event)).isEqualTo(1);
        send(event);
        send(event);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(attempts.get()).isEqualTo(5));
        assertThat(payments(event)).isEqualTo(1);
    }

    @Test
    void failureAfterCommitBeforeOffsetAcknowledgementDoesNotRepeatPayment() throws Exception {
        var event = event();
        var deliveries = new AtomicInteger();
        doAnswer(call -> {
            var result = call.callRealMethod();
            org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record = call.getArgument(0);
            if (record.value().contains(event.eventId().toString()) && deliveries.incrementAndGet() == 1) {
                throw new IllegalStateException("Simulated failure after commit before acknowledgement");
            }
            return result;
        }).when(listener).receive(any());
        send(event);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(deliveries.get()).isEqualTo(2));
        assertThat(payments(event)).isEqualTo(1);
        assertThat(processed(event)).isEqualTo(1);
    }

    @Test
    void topologyContainsMainThreeRetriesAndDeadLetterWithThreePartitions() throws Exception {
        var names = List.of(BookingCreated.TOPIC, BookingCreated.TOPIC + "-retry-0",
                BookingCreated.TOPIC + "-retry-1", BookingCreated.TOPIC + "-retry-2", BookingCreated.TOPIC + "-dlt");
        try (var admin = org.apache.kafka.clients.admin.Admin.create(Map.of("bootstrap.servers", broker.getBrokersAsString()))) {
            var topics = admin.describeTopics(names).allTopicNames().get();
            assertThat(topics.keySet()).containsExactlyInAnyOrderElementsOf(names);
            topics.values().forEach(topic -> assertThat(topic.partitions()).hasSize(3));
        }
    }

    @Test
    void concurrentDuplicateDeliveriesHaveOneBusinessEffect() throws Exception {
        var event = event();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> {
                start.await();
                handler.handle(event);
                return null;
            }));
            start.countDown();
            for (var future : futures) future.get(15, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(payments(event)).isEqualTo(1);
        assertThat(processed(event)).isEqualTo(1);
    }

    @Test
    void malformedEventIsIsolatedWithoutBusinessEffect() throws Exception {
        String malformed = "{invalid-" + UUID.randomUUID();
        kafka.send(BookingCreated.TOPIC, UUID.randomUUID().toString(), malformed).get();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.sql("SELECT COUNT(*) FROM failed_events WHERE payload = ?")
                        .param(malformed).query(Integer.class).single()).isEqualTo(1));
    }
}
