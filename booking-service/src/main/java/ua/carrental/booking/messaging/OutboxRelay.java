package ua.carrental.booking.messaging;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.carrental.events.BookingCreated;

@Service
@Profile("!http-demo")
@ConditionalOnProperty(name = "messaging.enabled", havingValue = "true")
public class OutboxRelay {
    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka) {
        this.jdbc = jdbc;
        this.kafka = kafka;
    }

    @Transactional
    public int publishBatch() {
        var pending = jdbc.sql("SELECT event_id, aggregate_id, payload FROM outbox_events WHERE published_at IS NULL ORDER BY created_at, event_id LIMIT 20 FOR UPDATE SKIP LOCKED")
                .query((rs, row) -> new Pending(rs.getObject("event_id", UUID.class), rs.getObject("aggregate_id", UUID.class), rs.getString("payload"))).list();
        for (var event : pending) {
            try {
                kafka.send(BookingCreated.TOPIC, event.aggregateId().toString(), event.payload()).get(15, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Outbox publication interrupted", exception);
            } catch (Exception exception) {
                throw new IllegalStateException("Outbox publication failed", exception);
            }
            jdbc.sql("UPDATE outbox_events SET published_at = CURRENT_TIMESTAMP WHERE event_id = ?")
                    .param(event.eventId()).update();
        }
        return pending.size();
    }

    private record Pending(UUID eventId, UUID aggregateId, String payload) { }
}
