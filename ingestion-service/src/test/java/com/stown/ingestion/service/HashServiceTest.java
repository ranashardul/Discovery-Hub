package com.stown.ingestion.service;

import com.stown.ingestion.api.IngestionRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HashServiceTest {

    private final HashService hashService = new HashService();

    @Test
    void producesStableSha256HexKey() {
        String key = hashService.calculateDeduplicationKey(request(
                List.of("bob@example.com"),
                "Subject"
        ));

        assertThat(key).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(hashService.calculateDeduplicationKey(request(
                List.of("bob@example.com"),
                "Subject"
        ))).isEqualTo(key);
    }

    @Test
    void ignoresRecipientOrder() {
        String first = hashService.calculateDeduplicationKey(request(
                List.of("bob@example.com", "carol@example.com"),
                "Subject"
        ));
        String second = hashService.calculateDeduplicationKey(request(
                List.of("carol@example.com", "bob@example.com"),
                "Subject"
        ));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentContentProducesDifferentKey() {
        String first = hashService.calculateDeduplicationKey(request(
                List.of("bob@example.com"),
                "Subject"
        ));
        String second = hashService.calculateDeduplicationKey(request(
                List.of("bob@example.com"),
                "Another subject"
        ));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void toleratesMissingThreadId() {
        IngestionRequest request = request(List.of("bob@example.com"), "Subject");
        request.setThreadId(null);

        assertThat(hashService.calculateDeduplicationKey(request)).hasSize(64);
    }

    private IngestionRequest request(List<String> recipients, String subject) {
        IngestionRequest request = new IngestionRequest();

        request.setCommunicationType("EMAIL");
        request.setSender("alice@example.com");
        request.setRecipients(recipients);
        request.setSubject(subject);
        request.setBody("Body");
        request.setMessageTimestamp(Instant.parse("2026-09-07T18:30:00Z"));
        request.setThreadId("thread-001");

        return request;
    }
}
