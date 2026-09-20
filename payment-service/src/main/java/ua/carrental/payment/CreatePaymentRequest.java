package ua.carrental.payment;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record CreatePaymentRequest(@NotNull UUID bookingId, @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount, @NotBlank @Pattern(regexp = "UAH|EUR|USD") String currency) {
}
