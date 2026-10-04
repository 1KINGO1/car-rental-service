package ua.carrental.booking;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BookingRepository {
    private static final RowMapper<Booking> MAPPER = (rs, row) -> new Booking(
            rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
            rs.getObject("vehicle_id", UUID.class), rs.getString("vehicle_brand"),
            rs.getString("vehicle_model"), rs.getString("vehicle_plate"), rs.getBigDecimal("daily_rate"),
            rs.getObject("start_date", java.time.LocalDate.class), rs.getObject("end_date", java.time.LocalDate.class),
            rs.getBigDecimal("total"), rs.getBoolean("customer_verified"), rs.getString("status"));

    private final JdbcClient jdbc;

    public BookingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Booking save(Booking booking) {
        jdbc.sql("""
                INSERT INTO bookings (id, customer_id, vehicle_id, vehicle_brand, vehicle_model,
                    vehicle_plate, daily_rate, start_date, end_date, total, customer_verified, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(booking.id(), booking.customerId(), booking.vehicleId(), booking.vehicleBrand(),
                        booking.vehicleModel(), booking.vehiclePlate(), booking.dailyRate(), booking.startDate(),
                        booking.endDate(), booking.total(), booking.customerVerified(), booking.status())
                .update();
        return find(booking.id()).orElseThrow();
    }

    public Optional<Booking> find(UUID id) {
        return jdbc.sql("SELECT * FROM bookings WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public List<Booking> findAll() {
        return jdbc.sql("SELECT * FROM bookings ORDER BY id").query(MAPPER).list();
    }
}
