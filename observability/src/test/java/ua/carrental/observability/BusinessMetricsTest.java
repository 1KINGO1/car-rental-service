package ua.carrental.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

class BusinessMetricsTest {
    @Test
    void recordsCommittedCreationsAndIgnoresRollbacks() throws Exception {
        var registry = new SimpleMeterRegistry();
        try (AutoCloseable closeRegistry = registry::close) {
            var metrics = new BusinessMetrics(registry);
            var counter = registry.get("car.rental.business.created").tag("entity", "booking").counter();
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                metrics.created(BusinessMetrics.Entity.BOOKING);
                assertThat(counter.count()).isZero();
                for (var callback : TransactionSynchronizationManager.getSynchronizations()) {
                    callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
                }
                assertThat(counter.count()).isZero();
            } finally {
                TransactionSynchronizationManager.clear();
            }
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                metrics.created(BusinessMetrics.Entity.BOOKING);
                assertThat(counter.count()).isZero();
                for (var callback : TransactionSynchronizationManager.getSynchronizations()) callback.afterCommit();
                assertThat(counter.count()).isEqualTo(1);
            } finally {
                TransactionSynchronizationManager.clear();
            }
            metrics.created(BusinessMetrics.Entity.BOOKING);
            assertThat(counter.count()).isEqualTo(2);
        }
    }
}

