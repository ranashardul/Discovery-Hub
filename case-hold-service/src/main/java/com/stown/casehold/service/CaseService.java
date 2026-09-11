package com.stown.casehold.service;

import com.stown.casehold.api.AddCaseCommunicationsRequest;
import com.stown.casehold.api.AddCaseCustodianRequest;
import com.stown.casehold.api.CaseCommunicationsResponse;
import com.stown.casehold.api.CaseCustodianResponse;
import com.stown.casehold.api.CaseResponse;
import com.stown.casehold.api.CommunicationRef;
import com.stown.casehold.api.CreateCaseRequest;
import com.stown.casehold.api.UpdateCaseRequest;
import com.stown.casehold.domain.CaseCommunicationEntity;
import com.stown.casehold.domain.CaseCustodianEntity;
import com.stown.casehold.domain.CaseEntity;
import com.stown.casehold.domain.CaseStatus;
import com.stown.casehold.domain.HoldStatus;
import com.stown.casehold.domain.MessageDocument;
import com.stown.casehold.messaging.CaseCreatedEvent;
import com.stown.casehold.messaging.CaseUpdatedEvent;
import com.stown.casehold.messaging.CommunicationAddedToCaseEvent;
import com.stown.casehold.messaging.EventOutboxWriter;
import com.stown.casehold.repository.CaseCommunicationRepository;
import com.stown.casehold.repository.CaseCustodianRepository;
import com.stown.casehold.repository.CaseRepository;
import com.stown.casehold.repository.HoldRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CaseService {

    private final CaseRepository caseRepository;
    private final CaseCommunicationRepository caseCommunicationRepository;
    private final CaseCustodianRepository caseCustodianRepository;
    private final HoldRepository holdRepository;
    private final MessageLookupService messageLookupService;
    private final EventOutboxWriter outbox;

    @Transactional
    public CaseResponse createCase(CreateCaseRequest request) {
        Instant now = Instant.now();
        UUID caseId = UUID.randomUUID();

        CaseEntity entity = CaseEntity.builder()
                .id(caseId)
                .name(request.caseName())
                .description(request.description())
                .status(CaseStatus.OPEN)
                .createdBy(request.createdBy())
                .createdAt(now)
                .updatedAt(now)
                .build();

        caseRepository.save(entity);

        outbox.write(CaseCreatedEvent.TYPE, caseId.toString(), CaseCreatedEvent.builder()
                .eventId(UUID.randomUUID())
                .caseId(caseId.toString())
                .caseName(entity.getName())
                .status(entity.getStatus().name())
                .createdBy(entity.getCreatedBy())
                .occurredAt(now)
                .build());

        log.info("Created case id={} name={}", caseId, entity.getName());

        return toResponse(entity, 0L, 0L, 0L);
    }

    @Transactional(readOnly = true)
    public List<CaseResponse> listCases(String status) {
        List<CaseEntity> cases = (status == null || status.isBlank())
                ? caseRepository.findAllByOrderByCreatedAtDesc()
                : caseRepository.findByStatusOrderByCreatedAtDesc(parseStatus(status));

        return cases.stream()
                .map(entity -> toResponse(
                        entity,
                        caseCommunicationRepository.countByCaseId(entity.getId()),
                        caseCustodianRepository.countByCaseId(entity.getId()),
                        holdRepository.countByCaseIdAndStatus(entity.getId(), HoldStatus.ACTIVE)
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public CaseResponse getCase(UUID caseId) {
        CaseEntity entity = requireCase(caseId);
        return toResponse(
                entity,
                caseCommunicationRepository.countByCaseId(caseId),
                caseCustodianRepository.countByCaseId(caseId),
                holdRepository.countByCaseIdAndStatus(caseId, HoldStatus.ACTIVE)
        );
    }

    @Transactional
    public CaseResponse updateCase(UUID caseId, UpdateCaseRequest request) {
        CaseEntity entity = requireCase(caseId);
        Instant now = Instant.now();

        String previousStatus = entity.getStatus().name();
        boolean changed = false;

        if (request.caseName() != null && !request.caseName().isBlank()) {
            entity.setName(request.caseName());
            changed = true;
        }
        if (request.description() != null) {
            entity.setDescription(request.description());
            changed = true;
        }
        if (request.status() != null && !request.status().isBlank()) {
            CaseStatus newStatus = parseStatus(request.status());
            entity.setStatus(newStatus);
            changed = true;
        }

        if (!changed) {
            throw new IllegalArgumentException("At least one of caseName, description or status must be supplied");
        }

        entity.setUpdatedAt(now);
        caseRepository.save(entity);

        outbox.write(CaseUpdatedEvent.TYPE, caseId.toString(), CaseUpdatedEvent.builder()
                .eventId(UUID.randomUUID())
                .caseId(caseId.toString())
                .caseName(entity.getName())
                .description(entity.getDescription())
                .status(entity.getStatus().name())
                .updatedBy(request.updatedBy())
                .occurredAt(now)
                .build());

        log.info(
                "Updated case id={} status {} -> {} by {}",
                caseId,
                previousStatus,
                entity.getStatus().name(),
                request.updatedBy()
        );

        return toResponse(
                entity,
                caseCommunicationRepository.countByCaseId(caseId),
                caseCustodianRepository.countByCaseId(caseId),
                holdRepository.countByCaseIdAndStatus(caseId, HoldStatus.ACTIVE)
        );
    }

    @Transactional
    public void removeCommunication(UUID caseId, String communicationId) {
        CaseEntity entity = requireCase(caseId);
        assertMutable(entity);

        List<CaseCommunicationEntity> references =
                caseCommunicationRepository.findByCaseIdOrderByAddedAtAsc(caseId).stream()
                        .filter(item -> item.getCommunicationId().equals(communicationId))
                        .toList();

        if (references.isEmpty()) {
            throw new IllegalArgumentException("Communication " + communicationId + " is not on this case");
        }

        caseCommunicationRepository.deleteAll(references);
        entity.setUpdatedAt(Instant.now());
        caseRepository.save(entity);

        log.info("Removed communication {} from case id={}", communicationId, caseId);
    }

    @Transactional
    public CaseCommunicationsResponse addCommunications(
            UUID caseId,
            AddCaseCommunicationsRequest request
    ) {
        CaseEntity entity = requireCase(caseId);

        // De-duplicate against existing associations and within the request itself.
        List<CommunicationRef> requested = request.communications();
        Set<String> seenInRequest = new HashSet<>();
        List<CommunicationRef> toAdd = new ArrayList<>();

        for (CommunicationRef ref : requested) {
            if (seenInRequest.contains(ref.communicationId())) {
                continue;
            }
            if (caseCommunicationRepository.existsByCaseIdAndCommunicationId(caseId, ref.communicationId())) {
                seenInRequest.add(ref.communicationId());
                continue;
            }
            seenInRequest.add(ref.communicationId());
            toAdd.add(ref);
        }

        Instant now = Instant.now();
        List<CaseCommunicationEntity> newlyAdded = new ArrayList<>(toAdd.size());

        for (CommunicationRef ref : toAdd) {
            CaseCommunicationEntity comm = CaseCommunicationEntity.builder()
                    .id(UUID.randomUUID())
                    .caseId(caseId)
                    .communicationId(ref.communicationId())
                    .communicationType(ref.communicationType())
                    .addedBy(request.addedBy())
                    .addedAt(now)
                    .build();

            newlyAdded.add(caseCommunicationRepository.save(comm));

            outbox.write(CommunicationAddedToCaseEvent.TYPE, caseId.toString(), CommunicationAddedToCaseEvent.builder()
                    .eventId(UUID.randomUUID())
                    .caseId(caseId.toString())
                    .communicationId(ref.communicationId())
                    .communicationType(ref.communicationType())
                    .addedBy(request.addedBy())
                    .occurredAt(now)
                    .build());
        }

        log.info(
                "Added {} communication(s) to case id={} ({} already associated)",
                newlyAdded.size(),
                caseId,
                requested.size() - newlyAdded.size()
        );

        List<CaseCommunicationEntity> all =
                caseCommunicationRepository.findByCaseIdOrderByAddedAtAsc(caseId);
        Set<String> addedIds = newlyAdded.stream()
                .map(CaseCommunicationEntity::getCommunicationId)
                .collect(Collectors.toSet());

        return buildResponse(caseId, all, addedIds);
    }

    @Transactional(readOnly = true)
    public CaseCommunicationsResponse listCommunications(UUID caseId) {
        requireCase(caseId);
        List<CaseCommunicationEntity> all =
                caseCommunicationRepository.findByCaseIdOrderByAddedAtAsc(caseId);

        return buildResponse(caseId, all, Set.of());
    }

    /**
     * Resolves every reference against the message store in one batch, then
     * assembles the response. Unresolvable references are still returned, with
     * {@code resolved=false}, so a bad identifier or an unreachable store never
     * hides the case record itself.
     */
    private CaseCommunicationsResponse buildResponse(
            UUID caseId,
            List<CaseCommunicationEntity> all,
            Set<String> newlyAddedIds
    ) {
        Map<String, MessageDocument> messages = messageLookupService.findByIds(
                all.stream().map(CaseCommunicationEntity::getCommunicationId).toList()
        );

        List<CaseCommunicationsResponse.CaseCommunicationItem> items = all.stream()
                .map(entity -> toItem(entity, messages.get(entity.getCommunicationId())))
                .toList();

        long unresolved = items.stream()
                .filter(item -> !item.message().resolved())
                .count();

        return new CaseCommunicationsResponse(
                caseId,
                items.size(),
                unresolved,
                items,
                items.stream()
                        .filter(item -> newlyAddedIds.contains(item.communicationId()))
                        .toList()
        );
    }

    @Transactional(readOnly = true)
    public List<CaseCustodianResponse> listCustodians(UUID caseId) {
        requireCase(caseId);
        return caseCustodianRepository.findByCaseIdOrderByAddedAtAsc(caseId).stream()
                .map(this::toCustodianResponse)
                .toList();
    }

    @Transactional
    public CaseCustodianResponse addCustodian(UUID caseId, AddCaseCustodianRequest request) {
        CaseEntity entity = requireCase(caseId);
        assertMutable(entity);

        if (caseCustodianRepository.existsByCaseIdAndCustodianId(caseId, request.custodianId())) {
            throw new IllegalArgumentException(request.custodianId() + " is already a custodian on this case");
        }

        Instant now = Instant.now();
        CaseCustodianEntity custodian = CaseCustodianEntity.builder()
                .id(UUID.randomUUID())
                .caseId(caseId)
                .custodianId(request.custodianId())
                .addedBy(request.addedBy())
                .addedAt(now)
                .build();

        caseCustodianRepository.save(custodian);
        entity.setUpdatedAt(now);
        caseRepository.save(entity);

        log.info("Added custodian {} to case id={}", request.custodianId(), caseId);

        return toCustodianResponse(custodian);
    }

    @Transactional
    public void removeCustodian(UUID caseId, String custodianId) {
        CaseEntity entity = requireCase(caseId);
        assertMutable(entity);

        if (!caseCustodianRepository.existsByCaseIdAndCustodianId(caseId, custodianId)) {
            throw new IllegalArgumentException("Custodian " + custodianId + " is not attached to this case");
        }

        caseCustodianRepository.deleteByCaseIdAndCustodianId(caseId, custodianId);
        entity.setUpdatedAt(Instant.now());
        caseRepository.save(entity);

        log.info("Removed custodian {} from case id={}", custodianId, caseId);
    }

    private void assertMutable(CaseEntity entity) {
        if (entity.getStatus() == CaseStatus.CLOSED) {
            throw new IllegalArgumentException("Case is closed");
        }
    }

    private CaseCustodianResponse toCustodianResponse(CaseCustodianEntity entity) {
        // The archive owns display metadata; the case stores only the identity.
        return new CaseCustodianResponse(
                entity.getCustodianId(),
                entity.getCustodianId(),
                entity.getCustodianId(),
                null,
                entity.getAddedAt(),
                entity.getAddedBy()
        );
    }

    private CaseEntity requireCase(UUID caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId.toString()));
    }

    private CaseStatus parseStatus(String status) {
        try {
            return CaseStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid case status: " + status + ". Allowed: OPEN, CLOSED"
            );
        }
    }

    private static CaseCommunicationsResponse.CaseCommunicationItem toItem(
            CaseCommunicationEntity entity,
            MessageDocument message
    ) {
        return new CaseCommunicationsResponse.CaseCommunicationItem(
                entity.getCommunicationId(),
                entity.getCommunicationType(),
                entity.getAddedAt(),
                CommunicationDetails.from(message)
        );
    }

    private static CaseResponse toResponse(
            CaseEntity entity,
            long communicationCount,
            long custodianCount,
            long activeHoldCount
    ) {
        return new CaseResponse(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getStatus().name(),
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                communicationCount,
                custodianCount,
                activeHoldCount
        );
    }
}
