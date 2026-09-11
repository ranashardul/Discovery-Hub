package com.stown.ingestion.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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

    @NotEmpty
    private List<@Email @NotBlank String> recipients;

    @NotBlank
    private String subject;

    @NotBlank
    private String body;

    @NotNull
    private Instant messageTimestamp;

    private String threadId;

    /** Stable identifier from the source system, used for idempotency. */
    private String externalMessageId;

    @Valid
    private List<AttachmentRequest> attachments;

    /**
     * Retention for this message alone, in minutes, instead of the period its
     * communication type would get.
     *
     * <p>Exists so retention and disposition can be demonstrated end to end
     * inside a presentation. The alternative — shortening the period for
     * {@code EMAIL} — applies to every email in the archive, so the next
     * disposition run would delete the whole corpus to expire one message.
     *
     * <p>Bounded by {@code app.retention.message-override-max} (5 minutes by
     * default) and refused outright when
     * {@code app.retention.message-override-enabled} is false, so it cannot
     * become a way to smuggle a short period into a real archive. Null means
     * the configured policy applies, which is what every real caller sends.
     */
    @Min(1)
    private Integer retentionMinutes;
}
