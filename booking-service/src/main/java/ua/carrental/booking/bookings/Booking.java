package ua.carrental.booking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record Booking(UUID id, UUID customerId, UUID vehicleId, String vehicleBrand,
                      String vehicleModel, String vehiclePlate, BigDecimal dailyRate,
                      LocalDate startDate, LocalDate endDate, BigDecimal total,
                      boolean customerVerified, String status) {
}
