package ua.carrental.fleet;

import java.math.BigDecimal;
import java.util.UUID;

public record Vehicle(UUID id, String brand, String model, String plate, BigDecimal dailyRate) {
}
