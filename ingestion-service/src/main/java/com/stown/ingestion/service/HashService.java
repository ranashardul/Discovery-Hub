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
                safe(request.getThreadId())
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