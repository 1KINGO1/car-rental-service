package ua.carrental.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {
    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Payment create(CreatePaymentRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO payments (id, booking_id, amount, currency, status) VALUES (?, ?, ?, ?, ?)")
                .params(id, request.bookingId(), request.amount(), request.currency(), "PENDING")
                .update();
        return find(id).orElseThrow();
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
