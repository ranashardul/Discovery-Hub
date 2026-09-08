package com.stown.search.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "search_index_failures")
public class SearchIndexFailure {

    /** Always the messageId, so retries update rather than duplicate. */
    @Id
    private String id;

    private String messageId;
    private String eventId;
    private int attempts;
    private String lastError;
    private Instant firstFailedAt;
    private Instant lastFailedAt;
    private boolean resolved;
    private Instant resolvedAt;
}
