package ua.carrental.booking.messaging;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import ua.carrental.events.BookingCreated;

@Repository
@Profile("!http-demo")
public class OutboxRepository {
    private final JdbcClient jdbc;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public OutboxRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(BookingCreated event) {
        jdbc.sql("INSERT INTO outbox_events (event_id, aggregate_id, payload) VALUES (?, ?, ?)")
                .params(event.eventId(), event.bookingId(), mapper.writeValueAsString(event)).update();
    }
}
