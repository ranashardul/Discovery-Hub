package com.stown.exportaudit.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.stown.exportaudit.config.CaseHoldProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * HTTP client for the Case & Hold service. Resolves the communication IDs that
 * belong to a case or a legal hold so the export service can fetch exactly
 * those messages from MongoDB instead of reading the entire collection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseHoldClient {

    private final RestClient caseHoldRestClient;
    private final CaseHoldProperties caseHoldProperties;

    /**
     * Returns the communication IDs linked to a case.
     */
    public List<String> getCaseCommunicationIds(String caseId) {
        log.debug("Fetching communications for caseId={} from Case & Hold", caseId);

        CaseCommunicationsResponse response = caseHoldRestClient.get()
                .uri("/api/v1/cases/{caseId}/communications", caseId)
                .retrieve()
                .body(CaseCommunicationsResponse.class);

        if (response == null || response.added() == null) {
            return List.of();
        }

        List<String> ids = new ArrayList<>();
        for (CommunicationItem item : response.added()) {
            ids.add(item.communicationId());
        }

        log.info("Case & Hold returned {} communications for caseId={}", ids.size(), caseId);
        return ids;
    }

    /**
     * Returns the communication IDs covered by a legal hold.
     */
    public List<String> getHoldCommunicationIds(String holdId) {
        log.debug("Fetching communications for holdId={} from Case & Hold", holdId);

        HoldCommunicationsResponse response = caseHoldRestClient.get()
                .uri("/api/v1/holds/{holdId}/communications", holdId)
                .retrieve()
                .body(HoldCommunicationsResponse.class);

        if (response == null || response.communications() == null) {
            return List.of();
        }

        List<String> ids = new ArrayList<>();
        for (CommunicationItem item : response.communications()) {
            ids.add(item.communicationId());
        }

        log.info("Case & Hold returned {} communications for holdId={}", ids.size(), holdId);
        return ids;
    }

    // Local DTOs matching the Case & Hold service's JSON responses. Only the
    // fields the export service needs are captured.

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CaseCommunicationsResponse(
            String caseId,
            long total,
            List<CommunicationItem> added,
            List<CommunicationItem> newlyAdded
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HoldCommunicationsResponse(
            String holdId,
            long total,
            List<CommunicationItem> communications
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommunicationItem(
            String communicationId,
            String communicationType,
            String addedAt
    ) {
    }
}
