package ua.carrental.booking.client;

import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import ua.carrental.booking.client.dto.EligibilityDto;

@HttpExchange("/api/customers")
public interface CustomerClient {

    @GetExchange("/{id}/eligibility")
    EligibilityDto eligibility(@PathVariable UUID id);
}
