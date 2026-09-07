package com.stown.ingestion.api;

import com.stown.ingestion.service.IngestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/ingestion")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionService ingestionService;

    @PostMapping("/messages")
    public ResponseEntity<?> ingest(
            @Valid @RequestBody IngestionRequest request
    ) {
        var savedMessage = ingestionService.ingest(request);

        return ResponseEntity.ok(Map.of(
                "messageId", savedMessage.getId(),
                "deduplicationKey", savedMessage.getDeduplicationKey(),
                "status", "INGESTED"
        ));
    }
}