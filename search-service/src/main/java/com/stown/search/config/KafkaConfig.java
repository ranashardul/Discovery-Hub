package com.stown.search.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
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
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(properties.getDeadLetterTopic(), -1)
        );

        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setMaxAttempts(properties.getRetryAttempts());
        backOff.setInitialInterval(properties.getRetryInitialIntervalMs());
        backOff.setMultiplier(properties.getRetryMultiplier());
        backOff.setMaxInterval(properties.getRetryMaxIntervalMs());

        return new DefaultErrorHandler(recoverer, backOff);
    }
}
