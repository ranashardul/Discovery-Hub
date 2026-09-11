package com.stown.exportaudit.service;

import tools.jackson.databind.ObjectMapper;
import com.stown.exportaudit.domain.AttachmentMetadata;
import com.stown.exportaudit.domain.ExportJobDocument;
import com.stown.exportaudit.domain.ExportManifest;
import com.stown.exportaudit.domain.ManifestItem;
import com.stown.exportaudit.domain.MessageDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Assembles an export package (ZIP) from the evidence returned by the
 * evidence provider.
 *
 * <p>The archive is laid out so each file answers one question:
 *
 * <ul>
 *   <li>{@code messages/} and {@code attachments/} — the evidence itself.</li>
 *   <li>{@code audit-report.txt} — the case and its chain of custody, for a
 *       reviewer to read.</li>
 *   <li>{@code audit-report.json} — the same facts, for a machine to parse.</li>
 *   <li>{@code manifest.json} — exactly what was exported, with a SHA-256 per
 *       item.</li>
 *   <li>{@code checksums.sha256} — the same digests in {@code sha256sum}
 *       format, so integrity can be checked without this service.</li>
 * </ul>
 *
 * <p>A package-level checksum is computed over the finished ZIP bytes and
 * returned to the caller, because it cannot be written into the archive it
 * describes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackageBuilder {

    private static final String MESSAGES_DIR = "messages/";
    private static final String ATTACHMENTS_DIR = "attachments/";
    private static final String MANIFEST_ENTRY = "manifest.json";
    private static final String AUDIT_REPORT_TEXT_ENTRY = "audit-report.txt";
    private static final String AUDIT_REPORT_JSON_ENTRY = "audit-report.json";
    private static final String CHECKSUMS_ENTRY = "checksums.sha256";

    private final S3StorageService storageService;
    private final ChecksumService checksumService;
    private final ObjectMapper objectMapper;
    private final CaseAuditReportBuilder auditReportBuilder;

    /**
     * Builds the export package for the supplied evidence.
     *
     * @param job      the export job being fulfilled, which carries the case,
     *                 scope and requesting actor the report is written for
     * @param messages evidence items, ordered chronologically by the provider
     */
    public PackageBuildResult build(
            ExportJobDocument job,
            List<MessageDocument> messages
    ) throws IOException {
        List<ManifestItem> items = new ArrayList<>();
        int attachmentCount = 0;

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (MessageDocument message : messages) {
                String entryName = MESSAGES_DIR + sanitize(message.getId()) + ".txt";
                byte[] messageBytes = renderMessage(message);
                String sha = checksumService.sha256Hex(messageBytes);

                putEntry(zip, entryName, messageBytes);

                items.add(new ManifestItem(
                        "MESSAGE",
                        entryName,
                        message.getId(),
                        null,
                        null,
                        messageBytes.length,
                        sha
                ));

                attachmentCount += addAttachments(zip, message, items);
            }

            // Written after the evidence so the report can reconcile itself
            // against the manifest entries that already exist.
            addAuditReport(zip, job, messages, items);

            // Covers every item written above. Deliberately not itself a
            // manifest entry: a checksum file cannot attest to itself.
            addChecksums(zip, items);

            // The manifest carries per-item checksums. The package-level
            // checksum cannot be included inside the ZIP (it is computed over
            // the final ZIP bytes), so it is left null here and returned
            // separately by the builder for the caller to persist.
            ExportManifest manifest = new ExportManifest(
                    job.getExportId(),
                    job.getCaseId(),
                    job.getScope() == null ? null : job.getScope().name(),
                    job.getRequestedBy(),
                    Instant.now(),
                    null,
                    messages.size(),
                    attachmentCount,
                    items
            );

            byte[] manifestBytes = objectMapper.writeValueAsBytes(manifest);
            putEntry(zip, MANIFEST_ENTRY, manifestBytes);
        }

        byte[] packageBytes = buffer.toByteArray();
        String packageSha256 = checksumService.sha256Hex(packageBytes);

        log.info(
                "Built export package exportId={} messages={} attachments={} bytes={}",
                job.getExportId(),
                messages.size(),
                attachmentCount,
                packageBytes.length
        );

        return new PackageBuildResult(
                packageBytes,
                new ExportManifest(
                        job.getExportId(),
                        job.getCaseId(),
                        job.getScope() == null ? null : job.getScope().name(),
                        job.getRequestedBy(),
                        Instant.now(),
                        packageSha256,
                        messages.size(),
                        attachmentCount,
                        items
                ),
                packageSha256
        );
    }

    private int addAttachments(
            ZipOutputStream zip,
            MessageDocument message,
            List<ManifestItem> items
    ) throws IOException {
        List<AttachmentMetadata> attachments = message.getAttachments();

        if (attachments == null || attachments.isEmpty()) {
            return 0;
        }

        int count = 0;

        for (AttachmentMetadata attachment : attachments) {
            String bucket = attachment.getS3Bucket() != null
                    ? attachment.getS3Bucket()
                    : storageService.getSourceBucket();
            byte[] content = storageService.readAttachment(bucket, attachment.getS3Key());
            String sha = checksumService.sha256Hex(content);

            String entryName = ATTACHMENTS_DIR + sanitize(message.getId()) + "/"
                    + sanitize(attachment.getFilename());

            putEntry(zip, entryName, content);

            items.add(new ManifestItem(
                    "ATTACHMENT",
                    entryName,
                    message.getId(),
                    attachment.getAttachmentId(),
                    attachment.getFilename(),
                    content.length,
                    sha
            ));

            count++;
        }

        return count;
    }

    /**
     * Writes the case audit report in both a readable and a parseable form, so
     * the package is self-contained evidence of its own provenance (FR-7).
     *
     * <p>A failure here is logged and swallowed: the report describes the
     * evidence, so losing it must not cost the export the evidence itself.
     */
    private void addAuditReport(
            ZipOutputStream zip,
            ExportJobDocument job,
            List<MessageDocument> messages,
            List<ManifestItem> items
    ) throws IOException {
        CaseAuditReport report;

        try {
            report = auditReportBuilder.build(job, messages, items);
        } catch (RuntimeException exception) {
            log.error(
                    "Could not build the audit report for exportId={} caseId={}; "
                            + "the evidence package is still complete",
                    job.getExportId(),
                    job.getCaseId(),
                    exception
            );
            return;
        }

        byte[] textBytes = report.text().getBytes(StandardCharsets.UTF_8);
        putEntry(zip, AUDIT_REPORT_TEXT_ENTRY, textBytes);
        items.add(new ManifestItem(
                "AUDIT_REPORT",
                AUDIT_REPORT_TEXT_ENTRY,
                job.getCaseId(),
                null,
                AUDIT_REPORT_TEXT_ENTRY,
                textBytes.length,
                checksumService.sha256Hex(textBytes)
        ));

        byte[] jsonBytes = objectMapper.writeValueAsBytes(report.data());
        putEntry(zip, AUDIT_REPORT_JSON_ENTRY, jsonBytes);
        items.add(new ManifestItem(
                "AUDIT_REPORT",
                AUDIT_REPORT_JSON_ENTRY,
                job.getCaseId(),
                null,
                AUDIT_REPORT_JSON_ENTRY,
                jsonBytes.length,
                checksumService.sha256Hex(jsonBytes)
        ));
    }

    /**
     * Emits the per-item digests in {@code sha256sum} format so a recipient can
     * verify the package with standard tooling:
     * {@code sha256sum -c checksums.sha256}.
     */
    private void addChecksums(ZipOutputStream zip, List<ManifestItem> items) throws IOException {
        StringBuilder checksums = new StringBuilder();

        for (ManifestItem item : items) {
            if (item.sha256() == null || item.sha256().isBlank()) {
                continue;
            }
            checksums.append(item.sha256()).append("  ").append(item.path()).append('\n');
        }

        putEntry(zip, CHECKSUMS_ENTRY, checksums.toString().getBytes(StandardCharsets.UTF_8));
    }

    private byte[] renderMessage(MessageDocument message) {
        StringBuilder text = new StringBuilder();

        text.append("Message ID: ").append(safe(message.getId())).append('\n');
        text.append("External Message ID: ").append(safe(message.getExternalMessageId())).append('\n');
        text.append("Type: ").append(safe(message.getCommunicationType())).append('\n');
        text.append("From: ").append(safe(message.getSender())).append('\n');

        List<String> recipients = message.getRecipients();
        text.append("To: ").append(recipients == null ? "" : String.join(", ", recipients)).append('\n');

        text.append("Date: ").append(safe(message.getMessageTimestamp())).append('\n');
        text.append("Thread: ").append(safe(message.getThreadId())).append('\n');
        text.append('\n');
        text.append("Subject: ").append(safe(message.getSubject())).append('\n');
        text.append('\n');
        text.append(safe(message.getBody())).append('\n');

        List<AttachmentMetadata> attachments = message.getAttachments();

        if (attachments != null && !attachments.isEmpty()) {
            text.append('\n').append("Attachments:").append('\n');

            for (AttachmentMetadata attachment : attachments) {
                text.append("  - ").append(safe(attachment.getFilename()))
                        .append(" (").append(attachment.getSizeBytes()).append(" bytes, sha256=")
                        .append(safe(attachment.getSha256())).append(")").append('\n');
            }
        }

        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void putEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private String sanitize(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String safe(Object value) {
        return value == null ? "" : value.toString();
    }
}
