package com.stown.exportaudit.api;

import com.stown.exportaudit.messaging.AuditEvent;
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

    /** Returns the audit history for a specific target entity, newest first. */
    @GetMapping
    public ResponseEntity<List<AuditEventResponse>> getAuditForTarget(@RequestParam String targetId) {
        List<AuditEventResponse> events = auditService.findByTargetId(targetId).stream()
                .map(AuditEventResponse::from)
                .toList();

        return ResponseEntity.ok(events);
    }
}
