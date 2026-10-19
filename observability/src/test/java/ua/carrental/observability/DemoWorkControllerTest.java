package ua.carrental.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class DemoWorkControllerTest {
    @Test
    void demonstrationEndpointIsDisabledWithoutExplicitProfile() {
        new ApplicationContextRunner().withUserConfiguration(ObservabilityConfiguration.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> assertThat(context).doesNotHaveBean(DemoWorkController.class));
    }

    @Test
    void rejectsUnboundedWork() {
        var controller = new DemoWorkController();
        for (int seconds : new int[]{-1, 0, 121, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> controller.work(seconds)).isInstanceOfSatisfying(ResponseStatusException.class,
                    exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
        }
    }
}
