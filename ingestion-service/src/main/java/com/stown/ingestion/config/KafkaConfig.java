package com.stown.ingestion.config;

import com.stown.ingestion.messaging.IngestionRequestedEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic ingestionRequestedTopic() {
        return TopicBuilder
                .name("ingestion.requested")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic messageIngestedTopic() {
        return TopicBuilder
                .name("message.ingested")
                .partitions(3)
                .replicas(1)
                .build();
    }
}