package com.stown.search.api;

import com.stown.search.config.SearchProperties;
import com.stown.search.index.SearchDocument;
import com.stown.search.service.SearchCriteria;
import com.stown.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;
    private final SearchProperties properties;

    @GetMapping
    public ResponseEntity<SearchResponse> search(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "communicationType", required = false) String communicationType,
            @RequestParam(name = "sender", required = false) String sender,
            @RequestParam(name = "recipient", required = false) String recipient,
            @RequestParam(name = "threadId", required = false) String threadId,
            @RequestParam(name = "dispositionStatus", required = false) String dispositionStatus,
            @RequestParam(name = "onHold", required = false) Boolean onHold,
            @RequestParam(name = "hasAttachments", required = false) Boolean hasAttachments,
            @RequestParam(name = "after", required = false) String after,
            @RequestParam(name = "before", required = false) String before,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "from", required = false) Integer from,
            @RequestParam(name = "size", required = false) Integer size
    ) {
        SearchRequest request = SearchRequest.builder()
                .q(query)
                .communicationType(communicationType)
                .sender(sender)
                .recipient(recipient)
                .threadId(threadId)
                .dispositionStatus(dispositionStatus)
                .onHold(onHold)
                .hasAttachments(hasAttachments)
                .after(after)
                .before(before)
                .sort(sort)
                .from(from)
                .size(size)
                .build();

        SearchCriteria criteria = SearchCriteria.of(request, properties.getMaxPageSize());

        return ResponseEntity.ok(searchService.search(criteria));
    }

    @GetMapping("/messages/{messageId}")
    public ResponseEntity<SearchDocument> findMessage(@PathVariable String messageId) {
        return searchService.findIndexedMessage(messageId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/stats")
    public ResponseEntity<SearchStatsResponse> stats() {
        return ResponseEntity.ok(searchService.stats());
    }
}
