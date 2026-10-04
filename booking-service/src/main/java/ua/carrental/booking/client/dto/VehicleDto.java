package ua.carrental.booking.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VehicleDto(
        UUID id,
        String brand,
        String model,
        String plate,
        BigDecimal dailyRate
) {
}
