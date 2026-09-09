package com.stown.casehold.service;

import com.stown.casehold.api.CommunicationRef;
import com.stown.casehold.api.CreateHoldRequest;
import com.stown.casehold.api.HoldCommunicationsResponse;
import com.stown.casehold.api.HoldCriteriaRequest;
import com.stown.casehold.api.HoldCriteriaResponse;
import com.stown.casehold.api.HoldResponse;
import com.stown.casehold.api.ReleaseHoldRequest;
import com.stown.casehold.domain.CaseEntity;
import com.stown.casehold.domain.CaseStatus;
import com.stown.casehold.domain.HoldCommunicationEntity;
import com.stown.casehold.domain.HoldCriteria;
import com.stown.casehold.domain.HoldEntity;
import com.stown.casehold.domain.HoldScope;
import com.stown.casehold.domain.HoldStatus;
import com.stown.casehold.domain.MessageDocument;
import com.stown.casehold.messaging.EventOutboxWriter;
import com.stown.casehold.messaging.HoldCreatedEvent;
import com.stown.casehold.messaging.HoldReleasedEvent;
import com.stown.casehold.repository.CaseRepository;
import com.stown.casehold.repository.HoldCommunicationRepository;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class HoldService {

    private final HoldRepository holdRepository;
    private final HoldCommunicationRepository holdCommunicationRepository;
    private final CaseRepository caseRepository;
    private final MessageLookupService messageLookupService;
    private final EventOutboxWriter outbox;

    @Transactional
    public HoldResponse createHold(UUID caseId, CreateHoldRequest request) {
        CaseEntity caseEntity = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId.toString()));

        if (caseEntity.getStatus() == CaseStatus.ARCHIVED) {
            throw new IllegalHoldStateException(
                    "Cannot place a hold on an archived case: " + caseId
            );
        }

        boolean hasCommunications = request.communications() != null && !request.communications().isEmpty();
        boolean hasCriteria = request.criteria() != null && !request.criteria().isEmpty();

        if (hasCommunications == hasCriteria) {
            // Exactly one source must be supplied.
            throw new IllegalArgumentException(
                    "Supply either 'communications' (for an explicit hold) or 'criteria' (for a rule-based hold)"
            );
        }

        HoldScope scope = hasCommunications ? HoldScope.COMMUNICATION : HoldScope.CRITERIA;
        Instant now = Instant.now();
        UUID holdId = UUID.randomUUID();

        HoldEntity.HoldEntityBuilder builder = HoldEntity.builder()
                .id(holdId)
                .caseId(caseId)
                .name(request.name())
                .description(request.description())
                .reason(request.reason())
                .status(HoldStatus.ACTIVE)
                .scope(scope)
                .createdBy(request.createdBy())
                .createdAt(now);

        HoldCriteria criteria = null;
        List<CommunicationRef> communicationIds = List.of();

        if (scope == HoldScope.CRITERIA) {
            criteria = toDomainCriteria(request.criteria());
            validateCriteria(criteria);
            builder.criteriaParticipants(join(criteria.getParticipants()))
                    .criteriaCommunicationTypes(join(criteria.getCommunicationTypes()))
                    .criteriaFromDate(criteria.getDateFrom())
                    .criteriaToDate(criteria.getDateTo());
        } else {
            communicationIds = request.communications();
            validateCommunicationIds(communicationIds);
        }

        HoldEntity hold = holdRepository.save(builder.build());

        List<String> emittedCommunicationIds = List.of();
        if (scope == HoldScope.COMMUNICATION) {
            List<HoldCommunicationEntity> saved = new ArrayList<>(communicationIds.size());
            Set<String> seen = new HashSet<>(communicationIds.size());

            for (CommunicationRef ref : communicationIds) {
                if (!seen.add(ref.communicationId())) {
                    continue;
                }
                if (holdCommunicationRepository.existsByHoldIdAndCommunicationId(holdId, ref.communicationId())) {
                    continue;
                }
                saved.add(holdCommunicationRepository.save(HoldCommunicationEntity.builder()
                        .id(UUID.randomUUID())
                        .holdId(holdId)
                        .communicationId(ref.communicationId())
                        .communicationType(ref.communicationType())
                        .createdAt(now)
                        .build()));
            }

            emittedCommunicationIds = saved.stream()
                    .map(HoldCommunicationEntity::getCommunicationId)
                    .toList();
        }

        outbox.write(HoldCreatedEvent.TYPE, holdId.toString(), HoldCreatedEvent.builder()
                .eventId(UUID.randomUUID())
                .holdId(holdId.toString())
                .caseId(caseId.toString())
                .name(hold.getName())
                .status(hold.getStatus().name())
                .scope(hold.getScope().name())
                .communicationIds(emittedCommunicationIds)
                .criteriaParticipants(criteria != null ? criteria.getParticipants() : null)
                .criteriaCommunicationTypes(criteria != null ? criteria.getCommunicationTypes() : null)
                .criteriaDateFrom(criteria != null ? criteria.getDateFrom() : null)
                .criteriaDateTo(criteria != null ? criteria.getDateTo() : null)
                .createdBy(hold.getCreatedBy())
                .occurredAt(now)
                .build());

        log.info(
                "Created hold id={} caseId={} scope={} by {}",
                holdId,
                caseId,
                scope.name(),
                request.createdBy()
        );

        return toResponse(hold, holdCommunicationRepository.countByHoldId(holdId));
    }

    @Transactional(readOnly = true)
    public List<HoldResponse> listHoldsForCase(UUID caseId) {
        if (!caseRepository.existsById(caseId)) {
            throw new CaseNotFoundException(caseId.toString());
        }
        return holdRepository.findByCaseIdOrderByCreatedAtDesc(caseId).stream()
                .map(hold -> toResponse(hold, holdCommunicationRepository.countByHoldId(hold.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public HoldResponse getHold(UUID holdId) {
        HoldEntity hold = requireHold(holdId);
        return toResponse(hold, holdCommunicationRepository.countByHoldId(holdId));
    }

    @Transactional(readOnly = true)
    public HoldCommunicationsResponse listHoldCommunications(UUID holdId) {
        requireHold(holdId);
        List<HoldCommunicationEntity> comms =
                holdCommunicationRepository.findByHoldIdOrderByCreatedAtAsc(holdId);

        Map<String, MessageDocument> messages = messageLookupService.findByIds(
                comms.stream().map(HoldCommunicationEntity::getCommunicationId).toList()
        );

        List<HoldCommunicationsResponse.HoldCommunicationItem> items = comms.stream()
                .map(entity -> toItem(entity, messages.get(entity.getCommunicationId())))
                .toList();

        long unresolved = items.stream()
                .filter(item -> !item.message().resolved())
                .count();

        // A hold is only truly in force once ingestion-service has projected it
        // onto the message data; holdCount > 0 is that confirmation.
        long enforced = items.stream()
                .filter(item -> item.message().resolved())
                .filter(item -> item.message().holdCount() != null && item.message().holdCount() > 0)
                .count();

        return new HoldCommunicationsResponse(
                holdId,
                items.size(),
                unresolved,
                enforced,
                items
        );
    }

    @Transactional
    public HoldResponse releaseHold(UUID holdId, ReleaseHoldRequest request) {
        HoldEntity hold = requireHold(holdId);

        if (hold.getStatus() == HoldStatus.RELEASED) {
            throw new IllegalHoldStateException("Hold " + holdId + " is already released");
        }

        Instant now = Instant.now();
        hold.setStatus(HoldStatus.RELEASED);
        hold.setReleasedBy(request.releasedBy());
        hold.setReleasedAt(now);
        holdRepository.save(hold);

        outbox.write(HoldReleasedEvent.TYPE, holdId.toString(), HoldReleasedEvent.builder()
                .eventId(UUID.randomUUID())
                .holdId(holdId.toString())
                .caseId(hold.getCaseId().toString())
                .status(hold.getStatus().name())
                .releasedBy(request.releasedBy())
                .releasedAt(now)
                .occurredAt(now)
                .build());

        log.info(
                "Released hold id={} caseId={} by {}",
                holdId,
                hold.getCaseId(),
                request.releasedBy()
        );

        return toResponse(hold, holdCommunicationRepository.countByHoldId(holdId));
    }

    private HoldEntity requireHold(UUID holdId) {
        return holdRepository.findById(holdId)
                .orElseThrow(() -> new HoldNotFoundException(holdId.toString()));
    }

    private void validateCommunicationIds(List<CommunicationRef> communications) {
        Set<String> seen = new HashSet<>(communications.size());
        for (CommunicationRef ref : communications) {
            if (ref.communicationId() == null || ref.communicationId().isBlank()) {
                throw new IllegalArgumentException("communicationId must not be blank");
            }
            if (!seen.add(ref.communicationId())) {
                throw new IllegalArgumentException(
                        "Duplicate communicationId in request: " + ref.communicationId()
                );
            }
        }
    }

    private void validateCriteria(HoldCriteria criteria) {
        if (criteria.isEmpty()) {
            throw new IllegalArgumentException("Hold criteria must contain at least one filter");
        }
        if (criteria.getDateFrom() != null && criteria.getDateTo() != null
                && criteria.getDateTo().isBefore(criteria.getDateFrom())) {
            throw new IllegalArgumentException("criteria.dateTo must not be before criteria.dateFrom");
        }
        if (criteria.getParticipants() != null) {
            for (String participant : criteria.getParticipants()) {
                if (participant == null || participant.isBlank()) {
                    throw new IllegalArgumentException("participants must not contain blank values");
                }
            }
        }
        if (criteria.getCommunicationTypes() != null) {
            for (String type : criteria.getCommunicationTypes()) {
                if (type == null || type.isBlank()) {
                    throw new IllegalArgumentException("communicationTypes must not contain blank values");
                }
            }
        }
    }

    private HoldCriteria toDomainCriteria(HoldCriteriaRequest request) {
        List<String> participants = request.participants() == null
                ? List.of()
                : request.participants().stream().filter(p -> p != null && !p.isBlank()).distinct().toList();
        List<String> types = request.communicationTypes() == null
                ? List.of()
                : request.communicationTypes().stream().filter(t -> t != null && !t.isBlank()).distinct().toList();

        return HoldCriteria.builder()
                .participants(participants.isEmpty() ? null : participants)
                .communicationTypes(types.isEmpty() ? null : types)
                .dateFrom(request.dateFrom())
                .dateTo(request.dateTo())
                .build();
    }

    private String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.join(",", values);
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return List.of(value.split(","));
    }

    private static HoldCommunicationsResponse.HoldCommunicationItem toItem(
            HoldCommunicationEntity entity,
            MessageDocument message
    ) {
        return new HoldCommunicationsResponse.HoldCommunicationItem(
                entity.getCommunicationId(),
                entity.getCommunicationType(),
                entity.getCreatedAt(),
                CommunicationDetails.from(message)
        );
    }

    private static HoldResponse toResponse(HoldEntity hold, long communicationCount) {
        HoldCriteriaResponse criteria = null;
        if (hold.getScope() == HoldScope.CRITERIA) {
            criteria = new HoldCriteriaResponse(
                    split(hold.getCriteriaParticipants()),
                    split(hold.getCriteriaCommunicationTypes()),
                    hold.getCriteriaFromDate(),
                    hold.getCriteriaToDate()
            );
        }

        return new HoldResponse(
                hold.getId(),
                hold.getCaseId(),
                hold.getName(),
                hold.getDescription(),
                hold.getReason(),
                hold.getStatus().name(),
                hold.getScope().name(),
                criteria,
                hold.getCreatedBy(),
                hold.getCreatedAt(),
                hold.getReleasedBy(),
                hold.getReleasedAt(),
                communicationCount
        );
    }
}
