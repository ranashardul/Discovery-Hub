package com.stown.casehold.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only projection of an attachment entry embedded in the {@code messages}
 * collection owned by ingestion-service.
 *
 * <p>Only the fields needed to describe an attachment are mapped; the S3
 * location is not, because this service never fetches the binary.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentMetadata {

    private String attachmentId;
    private String filename;
    private String contentType;
    private long sizeBytes;
}
