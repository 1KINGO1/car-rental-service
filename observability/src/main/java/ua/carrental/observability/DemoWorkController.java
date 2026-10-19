package ua.carrental.observability;

import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Safe, bounded load simulation; the bean exists only with the monitoring-demo profile. */
@RestController
public final class DemoWorkController {
    private final Semaphore slots = new Semaphore(5);

    @GetMapping("/api/monitoring/demo-work")
    public Map<String, Object> work(@RequestParam(defaultValue = "20") int seconds) {
        if (seconds < 1 || seconds > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "seconds must be between 1 and 120");
        }
        if (!slots.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "At most 5 demonstration tasks may run concurrently");
        }
        try {
            Thread.sleep(seconds * 1000L);
            return Map.of("status", "completed", "seconds", seconds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Demonstration interrupted");
        } finally {
            slots.release();
        }
    }
}
