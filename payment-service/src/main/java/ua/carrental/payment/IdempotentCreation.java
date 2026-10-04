package ua.carrental.payment;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service

public class IdempotentCreation {
    private final JdbcClient jdbc;
    private final PaymentRepository repository;

    public IdempotentCreation(JdbcClient jdbc, PaymentRepository repository) {
        this.jdbc = jdbc;
        this.repository = repository;

    }

    @Transactional
    public Payment create(String key, CreatePaymentRequest request) {
        if (!key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
        }
        String fingerprint = request.toString();
        jdbc.sql("INSERT INTO idempotency_keys (request_key, fingerprint) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(key, fingerprint).update();
        var stored = jdbc.sql("SELECT fingerprint, result_id FROM idempotency_keys WHERE request_key = ? FOR UPDATE")
                .param(key).query((rs, row) -> new Stored(rs.getString("fingerprint"), rs.getObject("result_id", UUID.class))).single();
        if (!stored.fingerprint().equals(fingerprint)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key has already been used for a different request");
        }
        if (stored.resultId() != null) return repository.find(stored.resultId()).orElseThrow();
        Payment result = repository.create(request);
        jdbc.sql("UPDATE idempotency_keys SET result_id = ? WHERE request_key = ?").params(result.id(), key).update();
        return result;
    }

    private record Stored(String fingerprint, UUID resultId) {
    }
}
