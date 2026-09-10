package com.stown.exportaudit.api;

import com.stown.exportaudit.messaging.AuditEvent;
import com.stown.exportaudit.repository.AuditEventQueryRepository;
import com.stown.exportaudit.service.AuditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    /** Upper bound on a page, so one request cannot pull the whole trail. */
    private static final int MAX_PAGE_SIZE = 200;

    private final AuditService auditService;

    /**
     * Records an audit event synchronously. There is no update or delete
     * endpoint; audit records are append-only.
     */
    @PostMapping
    public ResponseEntity<AuditEventResponse> record(@Valid @RequestBody AuditRequest request) {
        AuditEvent event = AuditEvent.builder()
                .eventId(request.getEventId() == null || request.getEventId().isBlank()
                        ? UUID.randomUUID().toString()
                        : request.getEventId())
                .caseId(request.getCaseId())
                .action(request.getAction())
                .targetType(request.getTargetType())
                .targetId(request.getTargetId())
                .actor(request.getActor())
                .status(request.getStatus())
                .timestamp(request.getTimestamp() == null ? Instant.now() : request.getTimestamp())
                .before(request.getBefore())
                .after(request.getAfter())
                .details(request.getDetails())
                .build();

        return ResponseEntity.accepted().body(
                AuditEventResponse.from(auditService.record(event))
        );
    }

    /** Returns the audit history for a case, newest first. */
    @GetMapping("/cases/{caseId}")
    public ResponseEntity<List<AuditEventResponse>> getAuditForCase(@PathVariable String caseId) {
        List<AuditEventResponse> events = auditService.findByCaseId(caseId).stream()
                .map(AuditEventResponse::from)
                .toList();

        return ResponseEntity.ok(events);
    }

    /**
     * Returns the audit history for a specific target entity, newest first.
     *
     * <p>Kept for callers that want every entry for one target without paging.
     * {@code GET /api/audit/search} is the general query.
     */
    @GetMapping
    public ResponseEntity<List<AuditEventResponse>> getAuditForTarget(@RequestParam String targetId) {
        List<AuditEventResponse> events = auditService.findByTargetId(targetId).stream()
                .map(AuditEventResponse::from)
                .toList();

        return ResponseEntity.ok(events);
    }

    /**
     * Filtered, paged view of the trail.
     *
     * <p>Every filter is optional and they combine. Paged because the whole
     * point of an append-only trail is that it grows without bound, and the
     * previous reads returned an entire case history on every request.
     */
    @GetMapping("/search")
    public ResponseEntity<AuditPageResponse> search(
            @RequestParam(required = false) String caseId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        if (page < 0) {
            throw new IllegalArgumentException("Parameter 'page' must not be negative");
        }
        if (size < 1) {
            throw new IllegalArgumentException("Parameter 'size' must be at least 1");
        }

        AuditService.AuditPage result = auditService.query(
                new AuditEventQueryRepository.AuditFilter(
                        caseId, actor, action, targetType, targetId, from, to
                ),
                page,
                Math.min(size, MAX_PAGE_SIZE)
        );

        return ResponseEntity.ok(new AuditPageResponse(
                result.entries().stream().map(AuditEventResponse::from).toList(),
                result.total(),
                result.page(),
                result.size()
        ));
    }

    /** One entry by its event id. */
    @GetMapping("/{eventId}")
    public ResponseEntity<AuditEventResponse> getEntry(@PathVariable String eventId) {
        return auditService.findByEventId(eventId)
                .map(AuditEventResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Distinct actors, for a filter dropdown. */
    @GetMapping("/actors")
    public ResponseEntity<List<String>> actors() {
        return ResponseEntity.ok(auditService.distinctActors());
    }
}
