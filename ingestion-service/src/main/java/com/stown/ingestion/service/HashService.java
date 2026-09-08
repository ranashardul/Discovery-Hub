package com.stown.ingestion.service;

import com.stown.ingestion.api.IngestionRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

@Service
public class HashService {

    /**
     * Canonicalizes the message and hashes it with SHA-256.
     *
     * <p>{@code externalMessageId} participates in the canonical form. A source
     * system identifier distinguishes two genuinely different messages that
     * happen to carry identical content, while re-submitting the same source
     * message still produces the same key and stays idempotent.
     */
    public String calculateDeduplicationKey(IngestionRequest request) {
        List<String> sortedRecipients = new ArrayList<>(
                request.getRecipients()
        );

        Collections.sort(sortedRecipients);

        String canonicalValue = String.join("|",
                safe(request.getCommunicationType()),
                safe(request.getSender()),
                String.join(",", sortedRecipients),
                safe(request.getSubject()),
                safe(request.getBody()),
                request.getMessageTimestamp().toString(),
                safe(request.getThreadId()),
                safe(request.getExternalMessageId())
        );

        return sha256(canonicalValue);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 algorithm is unavailable",
                    exception
            );
        }
    }
}