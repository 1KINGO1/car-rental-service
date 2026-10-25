package ua.carrental.observability;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.util.HashMap;
import java.util.Map;

public final class TraceCarrier {
    private static final TextMapPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

    private TraceCarrier() { }

    public static String capture() {
        var carrier = new HashMap<String, String>();
        PROPAGATOR.inject(Context.current(), carrier, Map::put);
        return carrier.getOrDefault("traceparent", "");
    }

    public static Context restore(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Context.root();
        }
        return PROPAGATOR.extract(Context.root(), Map.of("traceparent", traceparent), GETTER);
    }
}
