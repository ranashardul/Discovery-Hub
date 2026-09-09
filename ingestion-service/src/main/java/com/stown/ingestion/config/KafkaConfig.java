package com.stown.ingestion.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
public class KafkaConfig {

    public static final String INGESTION_REQUESTED_TOPIC = "ingestion.requested";
    public static final String INGESTION_REQUESTED_DLT = "ingestion.requested.dlt";
    public static final String MESSAGE_INGESTED_TOPIC = "message.ingested";
    public static final String MESSAGE_DISPOSED_TOPIC = "message.disposed";
    public static final String MESSAGE_DISPOSED_DLT = "message.disposed.dlt";

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

    @Bean
    public NewTopic messageDisposedTopic(
            @Value("${app.kafka.partitions:3}") int partitions
    ) {
        return TopicBuilder
                .name(MESSAGE_DISPOSED_TOPIC)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic messageDisposedDeadLetterTopic() {
        return TopicBuilder
                .name(MESSAGE_DISPOSED_DLT)
                .partitions(1)
                .replicas(1)
                .build();
    }

    /**
     * Consumer factory for {@code case-hold.events}, which is owned by the
     * case-hold service.
     *
     * <p>That producer serialises its outbox rows with a
     * {@code StringSerializer}, so values arrive as raw JSON text with no type
     * header, and a single topic carries every Case and Hold event type. The
     * default factory in {@code application.yaml} binds values to
     * {@code IngestionRequestedEvent}, so reusing it would fail on every
     * record. Values are delivered as String and parsed by the listener.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> caseHoldListenerFactory(
            KafkaProperties kafkaProperties,
            @Value("${app.case-hold.consumer-group:ingestion-service-holds}") String groupId
    ) {
        Map<String, Object> config = new HashMap<>();
        config.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                String.join(",", kafkaProperties.getBootstrapServers())
        );
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));

        return factory;
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
