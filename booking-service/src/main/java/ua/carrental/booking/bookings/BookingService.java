package ua.carrental.booking;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BookingService {
    private final BookingRepository repository;
    private final RentalCatalog catalog;

    public BookingService(BookingRepository repository, RentalCatalog catalog) {
        this.repository = repository;
        this.catalog = catalog;
    }

    public Booking create(CreateBookingRequest request) {
        RentalCatalog.Vehicle vehicle = catalog.vehicleFor(request.customerId(), request.vehicleId());
        long days = ChronoUnit.DAYS.between(request.startDate(), request.endDate());
        BigDecimal total = vehicle.dailyRate().multiply(BigDecimal.valueOf(days));
        return repository.save(new Booking(UUID.randomUUID(), request.customerId(), request.vehicleId(),
                vehicle.brand(), vehicle.model(), vehicle.plate(), vehicle.dailyRate(),
                request.startDate(), request.endDate(), total, true, "REQUESTED"));
    }
}
