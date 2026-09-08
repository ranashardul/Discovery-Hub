package com.stown.ingestion.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Slf4j
@Configuration
public class KafkaConfig {

    public static final String INGESTION_REQUESTED_TOPIC = "ingestion.requested";
    public static final String INGESTION_REQUESTED_DLT = "ingestion.requested.dlt";
    public static final String MESSAGE_INGESTED_TOPIC = "message.ingested";

    @Bean
    public NewTopic ingestionRequestedTopic(
            @Value("${app.kafka.partitions:3}") int partitions
    ) {
        return TopicBuilder
                .name(INGESTION_REQUESTED_TOPIC)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic ingestionRequestedDeadLetterTopic() {
        return TopicBuilder
                .name(INGESTION_REQUESTED_DLT)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic messageIngestedTopic(
            @Value("${app.kafka.partitions:3}") int partitions
    ) {
        return TopicBuilder
                .name(MESSAGE_INGESTED_TOPIC)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    /**
     * Retries a failing ingestion event with exponential backoff and routes it
     * to the dead-letter topic once the attempts are exhausted. The message ID
     * is already fixed at that point, so the event can be replayed later
     * without creating a new message identity.
     */
    @Bean
    public DefaultErrorHandler ingestionErrorHandler(
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
