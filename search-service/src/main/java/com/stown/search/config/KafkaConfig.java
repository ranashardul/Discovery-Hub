package com.stown.search.config;

import com.stown.search.messaging.MessageDisposedEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic messageIngestedTopic(SearchProperties properties) {
        return TopicBuilder
                .name(properties.getTopic())
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic messageIngestedDeadLetterTopic(SearchProperties properties) {
        return TopicBuilder
                .name(properties.getDeadLetterTopic())
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic messageDisposedTopic(SearchProperties properties) {
        return TopicBuilder
                .name(properties.getDisposedTopic())
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic messageDisposedDeadLetterTopic(SearchProperties properties) {
        return TopicBuilder
                .name(properties.getDisposedDeadLetterTopic())
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * Dedicated listener container for {@code message.disposed}.
     *
     * <p>The consumer defaults in {@code application.yaml} bind every value to
     * {@link com.stown.search.messaging.MessageIngestedEvent}, because the
     * producer sends a type header this service cannot resolve. Reusing that
     * factory here would deserialise a disposition into the wrong type, so the
     * default value type is overridden for this topic only.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, MessageDisposedEvent> disposedListenerFactory(
            KafkaProperties kafkaProperties,
            SearchProperties properties,
            KafkaTemplate<Object, Object> kafkaTemplate
    ) {
        Map<String, Object> config = new HashMap<>();
        config.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                String.join(",", kafkaProperties.getBootstrapServers())
        );
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.getDisposedConsumerGroup());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonDeserializer.class);
        config.put(JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        config.put(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, MessageDisposedEvent.class.getName());
        config.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.stown.search");

        ConcurrentKafkaListenerContainerFactory<String, MessageDisposedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.setCommonErrorHandler(errorHandler(
                kafkaTemplate,
                properties.getDisposedDeadLetterTopic(),
                properties
        ));

        return factory;
    }

    /**
     * Dead letter publishing needs to handle both deserialised events and raw
     * byte payloads coming from a failed deserialisation, hence the delegating
     * value serializer.
     */
    @Bean
    public ProducerFactory<Object, Object> producerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> config = new HashMap<>();
        config.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                String.join(",", kafkaProperties.getBootstrapServers())
        );
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);

        Map<Class<?>, Serializer<?>> keyDelegates = new LinkedHashMap<>();
        keyDelegates.put(byte[].class, new ByteArraySerializer());
        keyDelegates.put(String.class, new StringSerializer());

        Map<Class<?>, Serializer<?>> valueDelegates = new LinkedHashMap<>();
        valueDelegates.put(byte[].class, new ByteArraySerializer());
        valueDelegates.put(String.class, new StringSerializer());
        valueDelegates.put(Object.class, new JacksonJsonSerializer<>());

        return new DefaultKafkaProducerFactory<>(
                config,
                new DelegatingByTypeSerializer(keyDelegates, true),
                new DelegatingByTypeSerializer(valueDelegates, true)
        );
    }

    @Bean
    public KafkaTemplate<Object, Object> kafkaTemplate(ProducerFactory<Object, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<Object, Object> kafkaTemplate,
            SearchProperties properties
    ) {
        return errorHandler(kafkaTemplate, properties.getDeadLetterTopic(), properties);
    }

    private DefaultErrorHandler errorHandler(
            KafkaTemplate<Object, Object> kafkaTemplate,
            String deadLetterTopic,
            SearchProperties properties
    ) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(deadLetterTopic, -1)
        );

        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setMaxAttempts(properties.getRetryAttempts());
        backOff.setInitialInterval(properties.getRetryInitialIntervalMs());
        backOff.setMultiplier(properties.getRetryMultiplier());
        backOff.setMaxInterval(properties.getRetryMaxIntervalMs());

        return new DefaultErrorHandler(recoverer, backOff);
    }
}
