package com.stown.exportaudit.service;

import tools.jackson.databind.ObjectMapper;
import com.stown.exportaudit.domain.AttachmentMetadata;
import com.stown.exportaudit.domain.ExportManifest;
import com.stown.exportaudit.domain.ExportScope;
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
 * evidence provider. Each message is written in a readable text format and
 * every attachment binary is included. A {@code manifest.json} lists every
 * item with its SHA-256 checksum, and a package-level checksum is computed
 * over the ZIP bytes so the package can be verified later.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackageBuilder {

    private static final String MESSAGES_DIR = "messages/";
    private static final String ATTACHMENTS_DIR = "attachments/";
    private static final String MANIFEST_ENTRY = "manifest.json";

    private final S3StorageService storageService;
    private final ChecksumService checksumService;
    private final ObjectMapper objectMapper;

    /**
     * Builds the export package for the supplied evidence.
     *
     * @param exportId    job the package belongs to
     * @param caseId      case the evidence was exported for
     * @param scope       export scope
     * @param requestedBy actor that requested the export
     * @param messages    evidence items, ordered chronologically by the provider
     */
    public PackageBuildResult build(
            String exportId,
            String caseId,
            ExportScope scope,
            String requestedBy,
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

            // The manifest carries per-item checksums. The package-level
            // checksum cannot be included inside the ZIP (it is computed over
            // the final ZIP bytes), so it is left null here and returned
            // separately by the builder for the caller to persist.
            ExportManifest manifest = new ExportManifest(
                    exportId,
                    caseId,
                    scope == null ? null : scope.name(),
                    requestedBy,
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
                exportId,
                messages.size(),
                attachmentCount,
                packageBytes.length
        );

        return new PackageBuildResult(
                packageBytes,
                new ExportManifest(
                        exportId,
                        caseId,
                        scope == null ? null : scope.name(),
                        requestedBy,
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
                    : storageService.getExportBucket();
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
