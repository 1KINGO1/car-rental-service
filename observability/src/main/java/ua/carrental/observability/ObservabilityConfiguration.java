package ua.carrental.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Explicitly imported by all four services; no dependency on component-scan package layout. */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {
    @Bean
    ApiMetricsFilter apiMetricsFilter(MeterRegistry registry) {
        return new ApiMetricsFilter(registry);
    }

    @Bean
    BusinessMetrics businessMetrics(MeterRegistry registry) {
        return new BusinessMetrics(registry);
    }

    @Bean
    @Profile("monitoring-demo")
    DemoWorkController demoWorkController() {
        return new DemoWorkController();
    }
}
