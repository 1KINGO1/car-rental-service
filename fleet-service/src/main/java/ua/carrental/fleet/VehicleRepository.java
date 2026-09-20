package ua.carrental.fleet;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class VehicleRepository {
    private final JdbcClient jdbc;

    public VehicleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Vehicle create(CreateVehicleRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO vehicles (id, brand, model, plate, daily_rate) VALUES (?, ?, ?, ?, ?)")
                .params(id, request.brand(), request.model(), request.plate(), request.dailyRate())
                .update();
        return find(id).orElseThrow();
    }

    public Optional<Vehicle> find(UUID id) {
        return jdbc.sql("SELECT * FROM vehicles WHERE id = :id").param("id", id)
                .query((rs, row) -> new Vehicle(rs.getObject("id", UUID.class), rs.getString("brand"), rs.getString("model"), rs.getString("plate"), rs.getBigDecimal("daily_rate"))).optional();
    }

    public List<Vehicle> findAll() {
        return jdbc.sql("SELECT * FROM vehicles ORDER BY id")
                .query((rs, row) -> new Vehicle(rs.getObject("id", UUID.class), rs.getString("brand"), rs.getString("model"), rs.getString("plate"), rs.getBigDecimal("daily_rate"))).list();
    }
}
