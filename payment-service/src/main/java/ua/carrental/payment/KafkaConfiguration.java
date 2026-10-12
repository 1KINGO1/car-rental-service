package ua.carrental.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaRetryTopic;

@Configuration(proxyBeanMethods = false)
@EnableKafkaRetryTopic
@ConditionalOnProperty(name = "messaging.enabled", havingValue = "true")
public class KafkaConfiguration {
    @org.springframework.context.annotation.Bean
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler retryScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("kafka-retry-");
        return scheduler;
    }
}
