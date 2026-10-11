package ua.carrental.booking.bookings;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import ua.carrental.booking.catalog.RentalCatalog;

@Service
@Profile("!http-demo")
public class BookingService {
    private final BookingRepository repository;
    private final RentalCatalog catalog;
    private final ua.carrental.booking.messaging.OutboxRepository outbox;

    public BookingService(BookingRepository repository, RentalCatalog catalog, ua.carrental.booking.messaging.OutboxRepository outbox) {
        this.repository = repository;
        this.catalog = catalog;
        this.outbox = outbox;
    }

    @org.springframework.transaction.annotation.Transactional
    public Booking create(CreateBookingRequest request) {
        RentalCatalog.Vehicle vehicle = catalog.vehicleFor(request.customerId(), request.vehicleId());
        long days = ChronoUnit.DAYS.between(request.startDate(), request.endDate());
        BigDecimal total = vehicle.dailyRate().multiply(BigDecimal.valueOf(days));
        Booking booking = repository.save(new Booking(UUID.randomUUID(), request.customerId(), request.vehicleId(),
                vehicle.brand(), vehicle.model(), vehicle.plate(), vehicle.dailyRate(),
                request.startDate(), request.endDate(), total, true, "REQUESTED"));
        outbox.append(new ua.carrental.events.BookingCreated(UUID.randomUUID(), 1, "BookingCreated",
                java.time.Instant.now(), booking.id(), booking.customerId(), booking.total(), "UAH"));
        return booking;
    }
}
