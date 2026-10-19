package ua.carrental.customer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerRepository {
    private final JdbcClient jdbc;
    private final ua.carrental.observability.BusinessMetrics metrics;

    public CustomerRepository(JdbcClient jdbc, ua.carrental.observability.BusinessMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    public Customer create(CreateCustomerRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO customers (id, name, email, verified) VALUES (?, ?, ?, ?)")
                .params(id, request.name(), request.email(), request.verified())
                .update();
        Customer customer = find(id).orElseThrow();
        metrics.created(ua.carrental.observability.BusinessMetrics.Entity.CUSTOMER);
        return customer;
    }

    public Optional<Customer> find(UUID id) {
        return jdbc.sql("SELECT * FROM customers WHERE id = :id").param("id", id)
                .query((rs, row) -> new Customer(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email"), rs.getBoolean("verified"))).optional();
    }

    public List<Customer> findAll() {
        return jdbc.sql("SELECT * FROM customers ORDER BY id")
                .query((rs, row) -> new Customer(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email"), rs.getBoolean("verified"))).list();
    }
}
