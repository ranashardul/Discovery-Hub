package com.stown.search.index;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The shape of a document stored in the Elasticsearch {@code messages} index.
 *
 * <p>Timestamps are carried as ISO-8601 strings so that serialisation never
 * depends on a java.time aware Jackson module being registered inside the
 * Elasticsearch client's JsonpMapper.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchDocument {

    private String messageId;
    private String deduplicationKey;
    private String externalMessageId;
    private String communicationType;
    private String sender;
    private List<String> recipients;
    private String subject;
    private String body;
    private String threadId;
    private String messageTimestamp;
    private int attachmentCount;
    private List<String> attachmentFilenames;
    private String indexedAt;
}
