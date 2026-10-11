package ua.carrental.booking.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;
import ua.carrental.events.BookingCreated;

@Configuration(proxyBeanMethods = false)
@Profile("!http-demo")
@EnableScheduling
@ConditionalOnProperty(name = "messaging.enabled", havingValue = "true")
public class KafkaConfiguration {
    @Bean
    NewTopic bookingEvents(@Value("${messaging.replication-factor:1}") int replicas) {
        return TopicBuilder.name(BookingCreated.TOPIC).partitions(3).replicas(replicas).build();
    }
}
