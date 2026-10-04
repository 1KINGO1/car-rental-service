package ua.carrental.booking;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

public record CreateBookingRequest(
        @NotNull UUID customerId,
        @NotNull UUID vehicleId,
        @NotNull @FutureOrPresent LocalDate startDate,
        @NotNull LocalDate endDate) {
    @AssertTrue(message = "End date must be after start date and rental must not exceed 365 days")
    public boolean isPeriodValid() {
        return startDate == null || endDate == null
                || (endDate.isAfter(startDate) && !endDate.isAfter(startDate.plusDays(365)));
    }
}
