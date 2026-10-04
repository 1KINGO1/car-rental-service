package ua.carrental.booking.catalog;

import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange(url = "/api/customers", accept = "application/json")
public interface CustomerClient {
    @GetExchange("/{id}/eligibility")
    RentalCatalog.Eligibility getEligibility(@PathVariable("id") UUID id);
}
