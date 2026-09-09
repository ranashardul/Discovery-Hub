package com.stown.casehold.service;

import com.stown.casehold.domain.MessageDocument;
import com.stown.casehold.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Resolves stored communication references to the real message metadata held
 * in the {@code messages} collection owned by ingestion-service.
 *
 * <p>Lookups are batched into a single {@code $in} query so resolving a case
 * with a hundred communications costs one round trip rather than a hundred,
 * which matters when the store is a remote Atlas cluster.
 *
 * <p>A lookup failure is logged and degraded to "unresolved" rather than
 * propagated. Case and hold records are the authoritative legal artefacts and
 * live in PostgreSQL; they must remain readable when the message store is
 * unreachable. Callers see {@code resolved=false} instead of an error.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageLookupService {

    private final MessageRepository messageRepository;

    /**
     * Returns the messages for the supplied IDs, keyed by ID. IDs with no
     * matching message are simply absent from the map.
     */
    public Map<String, MessageDocument> findByIds(Collection<String> communicationIds) {
        if (communicationIds == null || communicationIds.isEmpty()) {
            return Map.of();
        }

        Set<String> distinctIds = new LinkedHashSet<>(communicationIds);

        try {
            Map<String, MessageDocument> byId = new HashMap<>(distinctIds.size());

            for (MessageDocument message : messageRepository.findAllById(distinctIds)) {
                byId.put(message.getId(), message);
            }

            int unresolved = distinctIds.size() - byId.size();
            if (unresolved > 0) {
                log.warn(
                        "Resolved {} of {} communication references; {} did not match a message",
                        byId.size(),
                        distinctIds.size(),
                        unresolved
                );
            }

            return byId;
        } catch (RuntimeException exception) {
            log.error(
                    "Could not resolve {} communication reference(s) against the message store: {}",
                    distinctIds.size(),
                    exception.getMessage()
            );

            return Map.of();
        }
    }

    /**
     * True when the supplied ID matches a message in the store. Returns false
     * if the store cannot be reached, so callers must treat this as "could not
     * confirm" rather than "definitely absent".
     */
    public boolean exists(String communicationId) {
        if (communicationId == null || communicationId.isBlank()) {
            return false;
        }

        try {
            return messageRepository.existsById(communicationId);
        } catch (RuntimeException exception) {
            log.error(
                    "Could not check communicationId={} against the message store: {}",
                    communicationId,
                    exception.getMessage()
            );

            return false;
        }
    }
}
