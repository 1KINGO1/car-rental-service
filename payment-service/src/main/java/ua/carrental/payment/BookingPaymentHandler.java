package ua.carrental.payment;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.carrental.events.BookingCreated;

@Service
public class BookingPaymentHandler {
    private final JdbcClient jdbc;
    private final PaymentRepository repository;
    private final ua.carrental.observability.BusinessMetrics metrics;

    public BookingPaymentHandler(JdbcClient jdbc, PaymentRepository repository, ua.carrental.observability.BusinessMetrics metrics) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.metrics = metrics;
    }

    @Transactional
    public void handle(BookingCreated event) {
        int claimed = jdbc.sql("INSERT INTO processed_events (event_id, booking_id) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(event.eventId(), event.bookingId()).update();
        if (claimed == 0) return;
        int inserted = jdbc.sql("INSERT INTO payments (id, booking_id, amount, currency, status) VALUES (?, ?, ?, ?, 'PENDING') ON CONFLICT DO NOTHING")
                .params(UUID.randomUUID(), event.bookingId(), event.amount(), event.currency()).update();
        Payment payment = jdbc.sql("SELECT id FROM payments WHERE booking_id = ?").param(event.bookingId())
                .query(UUID.class).optional().flatMap(repository::find).orElseThrow();
        if (payment.amount().compareTo(event.amount()) != 0 || !payment.currency().equals(event.currency())) {
            throw new IllegalArgumentException("Payment conflicts with booking event");
        }
        if (inserted > 0) metrics.created(ua.carrental.observability.BusinessMetrics.Entity.PAYMENT);
    }
}
