package ua.carrental.fleet;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {
    private final VehicleRepository repository;

    public VehicleController(VehicleRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    public ResponseEntity<Vehicle> create(@Valid @RequestBody CreateVehicleRequest request) {
        Vehicle result = repository.create(request);
        return ResponseEntity.created(URI.create("/api/vehicles/" + result.id())).body(result);
    }

    @GetMapping("/{id}")
    public Vehicle find(@PathVariable UUID id) {
        return repository.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vehicle not found"));
    }

    @GetMapping
    public List<Vehicle> findAll() {
        return repository.findAll();
    }
}
