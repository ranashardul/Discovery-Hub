package com.stown.ingestion.service;

import com.stown.ingestion.domain.MessageDocument;
import com.stown.ingestion.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * Read side of the {@code messages} collection. This is the only path by which
 * other services obtain message content: they call the ingestion read API,
 * which routes here, so the ingestion service keeps exclusive ownership of its
 * MongoDB collection (NFR-1 — no shared database schemas between services).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageQueryService {

    private final MessageRepository messageRepository;

    public MessageDocument findById(String id) {
        return messageRepository.findById(id)
                .orElseThrow(() -> new com.stown.ingestion.api.MessageNotFoundException(
                        "No message found for id " + id
                ));
    }

    /**
     * Returns every message whose id is in the supplied set, preserving no
     * particular order. Missing ids are silently omitted so a caller can ask
     * for a batch of evidence ids and receive only the ones that exist.
     */
    public List<MessageDocument> findByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        return messageRepository.findAllById(ids);
    }
}
