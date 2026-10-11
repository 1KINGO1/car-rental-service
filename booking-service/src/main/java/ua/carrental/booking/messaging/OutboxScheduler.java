package ua.carrental.booking.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!http-demo")
@ConditionalOnProperty(name = "messaging.enabled", havingValue = "true")
public class OutboxScheduler {
    private final OutboxRelay relay;
    public OutboxScheduler(OutboxRelay relay) { this.relay = relay; }

    @Scheduled(fixedDelayString = "${messaging.outbox-delay:1000}", initialDelayString = "${messaging.outbox-initial-delay:1000}")
    public void publish() { relay.publishBatch(); }
}
