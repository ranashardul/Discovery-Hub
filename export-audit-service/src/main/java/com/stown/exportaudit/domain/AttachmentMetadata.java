package com.stown.exportaudit.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only view of an attachment owned by the ingestion service. The export
 * service fetches the binary from S3 using {@code s3Bucket} and {@code s3Key}.
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
    private String sha256;
    private String s3Bucket;
    private String s3Key;
    private String s3Url;
}
