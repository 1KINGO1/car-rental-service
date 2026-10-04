package ua.carrental.payment;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentRepository repository;
    private final IdempotentCreation creation;

    public PaymentController(PaymentRepository repository, IdempotentCreation creation) {
        this.repository = repository;
        this.creation = creation;
    }

    @PostMapping
    public ResponseEntity<Payment> create(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreatePaymentRequest request) {
        Payment result = creation.create(key, request);
        return ResponseEntity.created(URI.create("/api/payments/" + result.id())).body(result);
    }

    @GetMapping("/{id}")
    public Payment find(@PathVariable UUID id) {
        return repository.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
    }

    @GetMapping
    public List<Payment> findAll() {
        return repository.findAll();
    }
}
