package ua.carrental.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Counts committed creations, not retries, idempotent replays, or rolled-back work. */
public final class BusinessMetrics {
    public enum Entity { CUSTOMER, VEHICLE, BOOKING, PAYMENT }
    private final Map<Entity, Counter> created = new EnumMap<>(Entity.class);

    public BusinessMetrics(MeterRegistry registry) {
        for (Entity entity : Entity.values()) {
            created.put(entity, Counter.builder("car.rental.business.created")
                    .description("Successfully committed new business entities")
                    .tag("entity", entity.name().toLowerCase(Locale.ROOT)).register(registry));
        }
    }

    public void created(Entity entity) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { created.get(entity).increment(); }
            });
        } else {
            created.get(entity).increment();
        }
    }
}
