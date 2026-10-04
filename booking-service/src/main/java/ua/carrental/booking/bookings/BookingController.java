package ua.carrental.booking;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {
    private final BookingRepository repository;
    private final BookingService service;

    public BookingController(BookingRepository repository, BookingService service) {
        this.repository = repository;
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Booking> create(@Valid @RequestBody CreateBookingRequest request) {
        Booking result = service.create(request);
        return ResponseEntity.created(URI.create("/api/bookings/" + result.id())).body(result);
    }

    @GetMapping("/{id}")
    public Booking find(@PathVariable UUID id) {
        return repository.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));
    }

    @GetMapping
    public List<Booking> findAll() {
        return repository.findAll();
    }
}
