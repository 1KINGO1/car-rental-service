package ua.carrental.observability;

import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceCarrierTest {
    @Test
    void preservesContextAcrossOutboxAndRestoresScope() {
        String header = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
        assertThat(TraceCarrier.capture()).isEmpty();
        try (var scope = TraceCarrier.restore(header).makeCurrent()) {
            assertThat(TraceCarrier.capture()).isEqualTo(header);
            assertThat(Span.current().getSpanContext().isRemote()).isTrue();
        }
        assertThat(TraceCarrier.capture()).isEmpty();
    }

    @Test
    void rejectsInvalidAndMissingParentsWithoutLeakingSchedulerContext() {
        for (String value : new String[]{"", "invalid", "00-00000000000000000000000000000000-0123456789abcdef-01"}) {
            assertThat(Span.fromContext(TraceCarrier.restore(value)).getSpanContext().isValid()).isFalse();
        }
        assertThat(Span.fromContext(TraceCarrier.restore(null)).getSpanContext().isValid()).isFalse();
    }
}
