package ua.carrental.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingCreated(UUID eventId, int schemaVersion, String eventType, Instant occurredAt,
                             UUID bookingId, UUID customerId, BigDecimal amount, String currency) {
    public static final String TOPIC = "rental.booking-created.v1";

    public BookingCreated {
        if (eventId == null || schemaVersion != 1 || !"BookingCreated".equals(eventType)
                || occurredAt == null || bookingId == null || customerId == null || amount == null
                || amount.signum() <= 0 || amount.scale() > 2 || amount.precision() - amount.scale() > 10
                || !java.util.Set.of("UAH", "EUR", "USD").contains(currency == null ? "" : currency)) {
            throw new IllegalArgumentException("Invalid BookingCreated v1 event");
        }
    }
}
