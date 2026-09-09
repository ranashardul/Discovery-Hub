package com.stown.exportaudit.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Slf4j
@Configuration
@EnableConfigurationProperties({ExportProperties.class, CaseHoldProperties.class})
public class KafkaConfig {

    @Bean
    public NewTopic exportRequestedTopic(
            ExportProperties properties,
            @Value("${app.kafka.partitions:3}") int partitions
    ) {
        return TopicBuilder
                .name(properties.getTopic())
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic exportRequestedDeadLetterTopic(ExportProperties properties) {
        return TopicBuilder
                .name(properties.getDeadLetterTopic())
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic exportCompletedTopic(
            ExportProperties properties,
            @Value("${app.kafka.partitions:3}") int partitions
    ) {
        return TopicBuilder
                .name(properties.getCompletedTopic())
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic auditEventsTopic(
            ExportProperties properties,
            @Value("${app.kafka.partitions:3}") int partitions
    ) {
        return TopicBuilder
                .name(properties.getAuditTopic())
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic caseHoldEventsTopic(
            CaseHoldProperties properties,
            @Value("${app.kafka.partitions:3}") int partitions
    ) {
        return TopicBuilder
                .name(properties.getEventsTopic())
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    /**
     * Retries a failing export event with exponential backoff and routes it to
     * the dead-letter topic once attempts are exhausted. The export ID is
     * fixed at request time, so the event can be replayed without creating a
     * duplicate package.
     */
    @Bean
    public DefaultErrorHandler exportErrorHandler(
            KafkaTemplate<Object, Object> kafkaTemplate,
            @Value("${app.kafka.retry.initial-interval-ms:1000}") long initialInterval,
            @Value("${app.kafka.retry.multiplier:2.0}") double multiplier,
            @Value("${app.kafka.retry.max-interval-ms:30000}") long maxInterval,
            @Value("${app.kafka.retry.max-elapsed-ms:120000}") long maxElapsedTime
    ) {
        ExponentialBackOff backOff = new ExponentialBackOff(initialInterval, multiplier);
        backOff.setMaxInterval(maxInterval);
        backOff.setMaxElapsedTime(maxElapsedTime);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new org.apache.kafka.common.TopicPartition(
                        record.topic() + ".dlt",
                        -1
                )
        );

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        errorHandler.setRetryListeners((record, exception, deliveryAttempt) ->
                log.warn(
                        "Retrying {} partition={} offset={} attempt={}: {}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        deliveryAttempt,
                        exception.getMessage()
                ));

        return errorHandler;
    }
}
