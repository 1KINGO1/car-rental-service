package ua.carrental.payment;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.*;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import ua.carrental.events.BookingCreated;

@Component
@ConditionalOnProperty(name = "messaging.enabled", havingValue = "true")
public class BookingEventListener {
    private final BookingPaymentHandler handler;
    private final JdbcClient jdbc;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public BookingEventListener(BookingPaymentHandler handler, JdbcClient jdbc) {
        this.handler = handler;
        this.jdbc = jdbc;
    }

    @RetryableTopic(attempts = "4", backOff = @BackOff(delay = 1000, multiplier = 2),
            numPartitions = "3", replicationFactor = "${messaging.replication-factor:1}",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            exclude = IllegalArgumentException.class)
    @KafkaListener(topics = BookingCreated.TOPIC, groupId = "payment-bookings-v1")
    public void receive(ConsumerRecord<String, String> record) {
        BookingCreated event;
        try {
            event = mapper.readValue(record.value(), BookingCreated.class);
            if (!event.bookingId().toString().equals(record.key())) throw new IllegalArgumentException("Invalid event key");
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid booking event", exception);
        }
        handler.handle(event);
    }

    @DltHandler
    public void deadLetter(ConsumerRecord<String, String> record) {
        jdbc.sql("INSERT INTO failed_events (topic, partition_id, record_offset, payload) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")
                .params(record.topic(), record.partition(), record.offset(), record.value() == null ? "null" : record.value()).update();
    }
}
