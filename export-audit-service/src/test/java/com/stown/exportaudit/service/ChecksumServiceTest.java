package com.stown.exportaudit.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ChecksumServiceTest {

    private final ChecksumService checksumService = new ChecksumService();

    @Test
    void producesStableSha256Hex() {
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        String sha = checksumService.sha256Hex(content);

        assertThat(sha).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(checksumService.sha256Hex(content)).isEqualTo(sha);
    }

    @Test
    void differentContentProducesDifferentHash() {
        byte[] first = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] second = "world".getBytes(StandardCharsets.UTF_8);

        assertThat(checksumService.sha256Hex(first))
                .isNotEqualTo(checksumService.sha256Hex(second));
    }

    @Test
    void matchesKnownSha256Value() {
        // SHA-256 of "abc" is a well-known constant.
        byte[] content = "abc".getBytes(StandardCharsets.UTF_8);

        assertThat(checksumService.sha256Hex(content))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
