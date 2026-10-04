package ua.carrental.booking.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bookings/catalog")
public class CatalogController {
    private final RentalCatalog catalog;

    public CatalogController(RentalCatalog catalog) {
        this.catalog = catalog;
    }

    @PostMapping
    public RentalCatalog.CatalogResult vehicles(@Valid @RequestBody BatchRequest request) {
        return catalog.vehicles(request.ids());
    }

    public record BatchRequest(@NotNull @Size(max = 100) List<@NotNull UUID> ids) {
    }
}
