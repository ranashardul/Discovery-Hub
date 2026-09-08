package com.stown.ingestion.api;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AttachmentRequest {

    @NotBlank
    private String filename;

    private String contentType;

    /** Base64-encoded binary. Uploaded to the object store by the API. */
    @NotBlank
    private String contentBase64;
}
