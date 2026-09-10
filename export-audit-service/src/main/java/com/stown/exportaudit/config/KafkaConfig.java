package com.stown.exportaudit.config;

import com.stown.exportaudit.messaging.AuditEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

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
    public NewTopic auditEventsDeadLetterTopic(ExportProperties properties) {
        return TopicBuilder
                .name(properties.getAuditTopic() + ".dlt")
                .partitions(1)
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

    @Bean
    public NewTopic caseHoldEventsDeadLetterTopic(CaseHoldProperties properties) {
        return TopicBuilder
                .name(properties.getEventsTopic() + ".dlt")
                .partitions(1)
                .replicas(1)
                .build();
    }

    /**
     * Consumer factory for {@code audit.events}.
     *
     * <p>The defaults in {@code application.yaml} bind every value to
     * {@link com.stown.exportaudit.messaging.ExportRequestedEvent}, because
     * that is the topic this service consumes most. Reusing that factory for
     * any other topic hands the listener the wrong type and Spring fails with
     * "Listener method could not be invoked with the incoming message" on
     * every single record, so each additional topic needs its own factory
     * naming its own default type.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AuditEvent> auditEventListenerFactory(
            KafkaProperties kafkaProperties,
            ExportProperties properties,
            KafkaTemplate<Object, Object> kafkaTemplate,
            DefaultErrorHandler exportErrorHandler
    ) {
        Map<String, Object> config = baseConsumerConfig(kafkaProperties);
        config.put(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, AuditEvent.class.getName());

        ConcurrentKafkaListenerContainerFactory<String, AuditEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.setCommonErrorHandler(exportErrorHandler);

        return factory;
    }

    /**
     * Consumer factory for {@code case-hold.events}.
     *
     * <p>That topic carries every Case and Hold event type, serialised by the
     * producer's outbox as raw JSON text with no type header, so values are
     * delivered as String and parsed by the listener.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> caseHoldListenerFactory(
            KafkaProperties kafkaProperties,
            DefaultErrorHandler exportErrorHandler
    ) {
        Map<String, Object> config = baseConsumerConfig(kafkaProperties);
        config.put(
                ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS,
                StringDeserializer.class
        );

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.setCommonErrorHandler(exportErrorHandler);

        return factory;
    }

    /**
     * Shared consumer settings.
     *
     * <p>The value deserialiser is wrapped in an {@link ErrorHandlingDeserializer}.
     * A deserialisation failure happens inside the consumer, before any
     * listener is invoked, so {@link DefaultErrorHandler} cannot see it and
     * cannot dead-letter it: the container retries the same offset forever and
     * every later record on that partition is blocked behind it. One
     * malformed payload would silently halt the whole audit trail. Wrapping
     * turns the failure into a null payload carrying the exception in a
     * header, which the error handler can then route to the dead-letter topic
     * so the partition keeps moving.
     */
    private Map<String, Object> baseConsumerConfig(KafkaProperties kafkaProperties) {
        Map<String, Object> config = new HashMap<>();
        config.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                String.join(",", kafkaProperties.getBootstrapServers())
        );
        config.put(
                ConsumerConfig.GROUP_ID_CONFIG,
                kafkaProperties.getConsumer().getGroupId()
        );
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ErrorHandlingDeserializer.class
        );
        config.put(
                ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS,
                JacksonJsonDeserializer.class
        );
        config.put(JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        config.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.stown.exportaudit");
        return config;
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
