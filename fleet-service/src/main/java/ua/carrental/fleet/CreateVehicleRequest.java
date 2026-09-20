package ua.carrental.fleet;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateVehicleRequest(@NotBlank @Size(max = 80) String brand, @NotBlank @Size(max = 80) String model, @NotBlank @Size(max = 20) String plate, @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal dailyRate) {
}
