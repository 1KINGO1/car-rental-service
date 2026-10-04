package ua.carrental.booking.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EligibilityDto(UUID customerId, boolean allowed) {
}
