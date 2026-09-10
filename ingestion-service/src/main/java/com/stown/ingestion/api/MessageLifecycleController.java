package com.stown.ingestion.api;

import com.stown.ingestion.domain.DispositionAudit;
import com.stown.ingestion.repository.DispositionRunRepository;
import com.stown.ingestion.repository.MessageRepository;
import com.stown.ingestion.service.DispositionService;
import com.stown.ingestion.service.MessageNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Retention, hold visibility and deletion for stored messages.
 *
 * <p>Deliberately contains no endpoint for placing or releasing a hold: that
 * is owned by the case-hold service, and holds reach this service only as
 * {@code case-hold.events}. What lives here is the deletion path, because this
 * service owns the message.
 */
@RestController
@RequestMapping("/api/ingestion")
@RequiredArgsConstructor
public class MessageLifecycleController {

    private static final int MAX_RUNS = 50;

    private final MessageRepository messageRepository;
    private final DispositionRunRepository runRepository;
    private final DispositionService dispositionService;

    /** Retention countdown and hold state for one message. */
    @GetMapping("/messages/{messageId}/retention")
    public ResponseEntity<RetentionStatusResponse> retentionStatus(@PathVariable String messageId) {
        return messageRepository.findById(messageId)
                .map(message -> RetentionStatusResponse.from(message, Instant.now()))
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new MessageNotFoundException(messageId));
    }

    /**
     * Deletes a message on request.
     *
     * <p>Refused with HTTP 409 when a legal hold covers it, which is the
     * demonstrable proof required by FR-4.6 that no part of the system can
     * delete held evidence.
     */
    @DeleteMapping("/messages/{messageId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String messageId) {
        DispositionAudit audit = dispositionService.deleteOnRequest(messageId);

        return ResponseEntity.ok(Map.of(
                "messageId", messageId,
                "status", "DISPOSED",
                "reason", audit.getReason(),
                "attachmentsPurged", audit.getS3Keys() == null ? 0 : audit.getS3Keys().size(),
                "disposedAt", audit.getCompletedAt()
        ));
    }

    /**
     * Runs a disposition pass now instead of waiting for the scheduler.
     *
     * <p><strong>This deletes data.</strong> It is refused with HTTP 409 when
     * {@code app.retention.enabled} is false, so an environment that has
     * disposition switched off cannot have its corpus deleted through the API,
     * and refused again while another pass is in flight. Retention periods and
     * the per-run delete cap still apply: this changes *when* a pass happens,
     * never *what* it is allowed to remove.
     */
    @PostMapping("/disposition/runs")
    public ResponseEntity<DispositionRunResponse> runDisposition() {
        return ResponseEntity
                .accepted()
                .body(DispositionRunResponse.from(dispositionService.disposeOnRequest()));
    }

    /** Recent disposition runs, newest first. */
    @GetMapping("/disposition/runs")
    public ResponseEntity<List<DispositionRunResponse>> runs(
            @RequestParam(defaultValue = "10") int limit
    ) {
        int capped = Math.min(Math.max(limit, 1), MAX_RUNS);

        List<DispositionRunResponse> runs = runRepository
                .findAllByOrderByStartedAtDesc(PageRequest.of(0, capped))
                .stream()
                .map(DispositionRunResponse::from)
                .toList();

        return ResponseEntity.ok(runs);
    }
}
