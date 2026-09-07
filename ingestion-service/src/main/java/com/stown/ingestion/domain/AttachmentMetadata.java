package com.stown.ingestion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private String s3Key;
}