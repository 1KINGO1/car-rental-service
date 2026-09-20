package ua.carrental.customer;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateCustomerRequest(@NotBlank @Size(max = 100) String name, @NotBlank @Email @Size(max = 200) String email, @NotNull Boolean verified) {
}
