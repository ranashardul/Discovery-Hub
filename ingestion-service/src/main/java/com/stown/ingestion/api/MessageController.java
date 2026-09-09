package com.stown.ingestion.api;

import com.stown.ingestion.domain.MessageDocument;
import com.stown.ingestion.service.MessageQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Read API for stored messages. This is the boundary other services use to
 * fetch message content and attachment metadata by id, instead of reading the
 * ingestion service's {@code messages} MongoDB collection directly. Keeping
 * the read behind this API is what lets each service own its data (NFR-1).
 */
@RestController
@RequestMapping("/api/ingestion/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageQueryService messageQueryService;

    @GetMapping("/{id}")
    public ResponseEntity<MessageResponse> getMessage(@PathVariable String id) {
        MessageDocument message = messageQueryService.findById(id);
        return ResponseEntity.ok(MessageResponse.from(message));
    }

    /**
     * Batch fetch. The export service resolves a list of communication ids from
     * the Case & Hold service and asks ingestion for the full content of all
     * of them in a single round-trip, which keeps large exports efficient
     * (NFR-3). Missing ids are omitted from the response.
     */
    @PostMapping("/batch")
    public ResponseEntity<List<MessageResponse>> getMessages(
            @Valid @RequestBody BatchGetMessagesRequest request
    ) {
        List<MessageResponse> messages = messageQueryService.findByIds(request.ids()).stream()
                .map(MessageResponse::from)
                .toList();

        return ResponseEntity.ok(messages);
    }

    public record BatchGetMessagesRequest(@NotEmpty Set<String> ids) {
    }
}
