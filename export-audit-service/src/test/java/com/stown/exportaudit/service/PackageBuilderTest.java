package com.stown.exportaudit.service;

import com.stown.exportaudit.domain.AttachmentMetadata;
import com.stown.exportaudit.domain.ExportManifest;
import com.stown.exportaudit.domain.ExportScope;
import com.stown.exportaudit.domain.ManifestItem;
import com.stown.exportaudit.domain.MessageDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackageBuilderTest {

    @Mock
    private S3StorageService storageService;

    @Mock
    private ChecksumService checksumService;

    private PackageBuilder packageBuilder;

    @BeforeEach
    void setUp() {
        // Use Spring Boot's default ObjectMapper; JavaTime is auto-registered.
        packageBuilder = new PackageBuilder(
                storageService,
                checksumService,
                new ObjectMapper()
        );

        // Return a deterministic-ish hash: use the content length so calls differ.
        when(checksumService.sha256Hex(any(byte[].class))).thenAnswer(
                invocation -> "sha-" + ((byte[]) invocation.getArgument(0)).length
        );
    }

    @Test
    void buildsPackageWithMessagesAndAttachments() throws IOException {
        byte[] attachmentContent = "attachment-bytes".getBytes();
        AttachmentMetadata attachment = AttachmentMetadata.builder()
                .attachmentId("att-1")
                .filename("report.pdf")
                .contentType("application/pdf")
                .sizeBytes(attachmentContent.length)
                .sha256("original-sha")
                .s3Bucket("source-bucket")
                .s3Key("messages/msg-1/attachments/att-1/report.pdf")
                .build();

        MessageDocument message = MessageDocument.builder()
                .id("msg-1")
                .deduplicationKey("dedup-1")
                .externalMessageId("ext-1")
                .communicationType("EMAIL")
                .sender("alice@example.com")
                .recipients(List.of("bob@example.com"))
                .subject("Subject")
                .body("Body")
                .messageTimestamp(Instant.parse("2026-09-01T10:00:00Z"))
                .threadId("thread-1")
                .attachments(List.of(attachment))
                .build();

        when(storageService.readAttachment("source-bucket",
                "messages/msg-1/attachments/att-1/report.pdf"))
                .thenReturn(attachmentContent);

        PackageBuildResult result = packageBuilder.build(
                "export-1",
                "case-1",
                ExportScope.CASE,
                "investigator@example.com",
                List.of(message)
        );

        assertThat(result.packageBytes()).isNotEmpty();
        assertThat(result.manifest().messageCount()).isEqualTo(1);
        assertThat(result.manifest().attachmentCount()).isEqualTo(1);
        assertThat(result.manifest().items()).hasSize(2);
        assertThat(result.manifest().caseId()).isEqualTo("case-1");
        assertThat(result.manifest().scope()).isEqualTo("CASE");
        assertThat(result.packageSha256()).startsWith("sha-");

        // Verify the ZIP contains the expected entries.
        List<String> entryNames = listZipEntries(result.packageBytes());
        assertThat(entryNames).contains("messages/msg-1.txt");
        assertThat(entryNames).contains("attachments/msg-1/report.pdf");
        assertThat(entryNames).contains("manifest.json");

        // Verify the manifest items.
        ManifestItem messageItem = result.manifest().items().stream()
                .filter(i -> "MESSAGE".equals(i.type()))
                .findFirst().orElseThrow();
        assertThat(messageItem.messageId()).isEqualTo("msg-1");
        assertThat(messageItem.path()).isEqualTo("messages/msg-1.txt");

        ManifestItem attachmentItem = result.manifest().items().stream()
                .filter(i -> "ATTACHMENT".equals(i.type()))
                .findFirst().orElseThrow();
        assertThat(attachmentItem.filename()).isEqualTo("report.pdf");
        assertThat(attachmentItem.attachmentId()).isEqualTo("att-1");
    }

    @Test
    void handlesMessageWithoutAttachments() throws IOException {
        MessageDocument message = MessageDocument.builder()
                .id("msg-2")
                .deduplicationKey("dedup-2")
                .communicationType("CHAT")
                .sender("carol@example.com")
                .recipients(List.of("dave@example.com"))
                .subject("Chat")
                .body("Hello")
                .messageTimestamp(Instant.parse("2026-09-02T10:00:00Z"))
                .build();

        PackageBuildResult result = packageBuilder.build(
                "export-2",
                "case-2",
                ExportScope.LEGAL_HOLD,
                "compliance@example.com",
                List.of(message)
        );

        assertThat(result.manifest().messageCount()).isEqualTo(1);
        assertThat(result.manifest().attachmentCount()).isEqualTo(0);
        assertThat(result.manifest().items()).hasSize(1);
        assertThat(result.manifest().scope()).isEqualTo("LEGAL_HOLD");

        List<String> entryNames = listZipEntries(result.packageBytes());
        assertThat(entryNames).contains("messages/msg-2.txt");
        assertThat(entryNames).contains("manifest.json");
    }

    @Test
    void emptyEvidenceProducesValidPackageWithZeroCounts() throws IOException {
        PackageBuildResult result = packageBuilder.build(
                "export-3",
                "case-3",
                ExportScope.CASE,
                "investigator@example.com",
                List.of()
        );

        assertThat(result.manifest().messageCount()).isZero();
        assertThat(result.manifest().attachmentCount()).isZero();
        assertThat(result.manifest().items()).isEmpty();

        List<String> entryNames = listZipEntries(result.packageBytes());
        assertThat(entryNames).containsExactly("manifest.json");
    }

    private List<String> listZipEntries(byte[] zipBytes) throws IOException {
        List<String> names = new java.util.ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
