package ua.carrental.customer;

import java.math.BigDecimal;
import java.util.UUID;

public record Customer(UUID id, String name, String email, boolean verified) {
}
