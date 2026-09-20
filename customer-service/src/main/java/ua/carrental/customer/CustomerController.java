package ua.carrental.customer;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    private final CustomerRepository repository;

    public CustomerController(CustomerRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    public ResponseEntity<Customer> create(@Valid @RequestBody CreateCustomerRequest request) {
        Customer result = repository.create(request);
        return ResponseEntity.created(URI.create("/api/customers/" + result.id())).body(result);
    }

    @GetMapping("/{id}")
    public Customer find(@PathVariable UUID id) {
        return repository.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
    }

    @GetMapping
    public List<Customer> findAll() {
        return repository.findAll();
    }

    @GetMapping("/{id}/eligibility")
    public Eligibility eligibility(@PathVariable UUID id) {
        Customer customer = find(id);
        return new Eligibility(customer.id(), customer.verified());
    }

    public record Eligibility(UUID customerId, boolean allowed) {
    }
}
