package com.stown.casehold.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    public static final String CASE_HOLD_EVENTS_TOPIC = "case-hold.events";

    /**
     * Single topic carrying every domain event. The event type is carried both
     * as the {@code event_type} message header and as a field in the JSON
     * payload, so consumers can route without type headers from the producer.
     */
    @Bean
    public NewTopic caseHoldEventsTopic() {
        return TopicBuilder
                .name(CASE_HOLD_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
