package com.stown.exportaudit.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.stown.exportaudit.config.CaseHoldProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
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
     * Case metadata for the audit report. Best-effort: the report is
     * supplementary to the evidence, so an unreachable Case &amp; Hold service
     * degrades the report rather than failing the export.
     */
    public CaseDetail getCase(String caseId) {
        try {
            return caseHoldRestClient.get()
                    .uri("/api/v1/cases/{caseId}", caseId)
                    .retrieve()
                    .body(CaseDetail.class);
        } catch (Exception exception) {
            log.warn("Could not read case {} for the audit report: {}", caseId, exception.getMessage());
            return null;
        }
    }

    /**
     * Legal holds placed on a case, for the audit report. Best-effort for the
     * same reason as {@link #getCase(String)}.
     */
    public List<HoldDetail> getHoldsForCase(String caseId) {
        try {
            HoldDetail[] holds = caseHoldRestClient.get()
                    .uri("/api/v1/cases/{caseId}/holds", caseId)
                    .retrieve()
                    .body(HoldDetail[].class);

            return holds == null ? List.of() : List.of(holds);
        } catch (Exception exception) {
            log.warn("Could not read holds for case {}: {}", caseId, exception.getMessage());
            return List.of();
        }
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

    /**
     * Case metadata as the Case &amp; Hold service reports it. Mirrors that
     * service's {@code CaseResponse}; fields it does not model (priority,
     * matter type, closure actor) are deliberately absent rather than faked.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CaseDetail(
            String caseId,
            String caseName,
            String description,
            String status,
            String createdBy,
            Instant createdAt,
            Instant updatedAt,
            long communicationCount,
            long activeHoldCount
    ) {
    }

    /** A legal hold on a case, mirroring the Case &amp; Hold {@code HoldResponse}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HoldDetail(
            String holdId,
            String caseId,
            String name,
            String description,
            String reason,
            String status,
            String scope,
            String createdBy,
            Instant createdAt,
            String releasedBy,
            Instant releasedAt,
            long communicationCount
    ) {
    }
}
