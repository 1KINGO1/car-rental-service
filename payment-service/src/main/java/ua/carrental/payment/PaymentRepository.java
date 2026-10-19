package ua.carrental.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {
    private final JdbcClient jdbc;
    private final ua.carrental.observability.BusinessMetrics metrics;

    public PaymentRepository(JdbcClient jdbc, ua.carrental.observability.BusinessMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    public Payment create(CreatePaymentRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO payments (id, booking_id, amount, currency, status) VALUES (?, ?, ?, ?, ?)")
                .params(id, request.bookingId(), request.amount(), request.currency(), "PENDING")
                .update();
        Payment payment = find(id).orElseThrow();
        metrics.created(ua.carrental.observability.BusinessMetrics.Entity.PAYMENT);
        return payment;
    }

    public Optional<Payment> find(UUID id) {
        return jdbc.sql("SELECT * FROM payments WHERE id = :id").param("id", id)
                .query((rs, row) -> new Payment(rs.getObject("id", UUID.class), rs.getObject("booking_id", UUID.class), rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("status"))).optional();
    }

    public List<Payment> findAll() {
        return jdbc.sql("SELECT * FROM payments ORDER BY id")
                .query((rs, row) -> new Payment(rs.getObject("id", UUID.class), rs.getObject("booking_id", UUID.class), rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("status"))).list();
    }
}
