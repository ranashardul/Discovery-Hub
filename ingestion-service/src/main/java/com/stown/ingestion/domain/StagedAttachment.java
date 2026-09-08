package com.stown.ingestion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An attachment binary that has been uploaded to the staging area of the object
 * store by the API. Only this reference travels through Kafka, never the binary
 * itself.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StagedAttachment {

    private String filename;
    private String contentType;
    private long sizeBytes;
    private String sha256;
    private String stagingBucket;
    private String stagingKey;
}
