package ua.carrental.payment;

import java.math.BigDecimal;
import java.util.UUID;

public record Payment(UUID id, UUID bookingId, BigDecimal amount, String currency, String status) {
}
