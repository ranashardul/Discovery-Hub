package com.stown.ingestion.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
public class IngestionRequest {

    @NotBlank
    private String communicationType;

    @NotBlank
    @Email
    private String sender;

    @NotNull
    private List<@Email String> recipients;

    @NotBlank
    private String subject;

    @NotBlank
    private String body;

    @NotNull
    private Instant messageTimestamp;

    private String threadId;
}