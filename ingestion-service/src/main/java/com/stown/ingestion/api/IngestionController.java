package com.stown.ingestion.api;

import com.stown.ingestion.domain.IngestionRequestDocument;
import com.stown.ingestion.service.IngestionRequestService;
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

@RestController
@RequestMapping("/api/ingestion")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionRequestService ingestionRequestService;

    /**
     * Accepts the request, publishes {@code ingestion.requested} and returns
     * immediately. Storage happens asynchronously in the ingestion worker.
     */
    @PostMapping("/messages")
    public ResponseEntity<IngestionResponse> ingest(
            @Valid @RequestBody IngestionRequest request
    ) {
        var accepted = ingestionRequestService.accept(request);
        IngestionRequestDocument document = accepted.request();

        return ResponseEntity.accepted().body(new IngestionResponse(
                document.getRequestId(),
                document.getDeduplicationKey(),
                document.getStatus(),
                accepted.duplicate(),
                document.getMessageId()
        ));
    }

    @GetMapping("/requests/{requestId}")
    public ResponseEntity<IngestionRequestStatusResponse> getRequest(
            @PathVariable String requestId
    ) {
        return ingestionRequestService.findByRequestId(requestId)
                .map(IngestionRequestStatusResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new IngestionRequestNotFoundException(
                        "No ingestion request found for requestId " + requestId
                ));
    }

    @GetMapping("/requests")
    public ResponseEntity<IngestionRequestStatusResponse> findRequest(
            @RequestParam(required = false) String deduplicationKey,
            @RequestParam(required = false) String externalMessageId
    ) {
        if (isBlank(deduplicationKey) && isBlank(externalMessageId)) {
            throw new IllegalArgumentException(
                    "Either deduplicationKey or externalMessageId must be provided"
            );
        }

        var found = isBlank(deduplicationKey)
                ? ingestionRequestService.findByExternalMessageId(externalMessageId)
                : ingestionRequestService.findByDeduplicationKey(deduplicationKey);

        return found
                .map(IngestionRequestStatusResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new IngestionRequestNotFoundException(
                        "No ingestion request found for the supplied identifier"
                ));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
