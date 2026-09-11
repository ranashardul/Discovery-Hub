package com.stown.exportaudit.service;

import com.stown.exportaudit.domain.AttachmentMetadata;
import com.stown.exportaudit.domain.ExportJobDocument;
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
import java.util.Map;
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

    @Mock
    private CaseAuditReportBuilder auditReportBuilder;

    private PackageBuilder packageBuilder;

    @BeforeEach
    void setUp() {
        // Use Spring Boot's default ObjectMapper; JavaTime is auto-registered.
        packageBuilder = new PackageBuilder(
                storageService,
                checksumService,
                new ObjectMapper(),
                auditReportBuilder
        );

        // Return a deterministic-ish hash: use the content length so calls differ.
        when(checksumService.sha256Hex(any(byte[].class))).thenAnswer(
                invocation -> "sha-" + ((byte[]) invocation.getArgument(0)).length
        );

        when(auditReportBuilder.build(any(), any(), any())).thenReturn(
                new CaseAuditReport("CASE AUDIT REPORT\n", Map.of("caseId", "case-1"))
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

        PackageBuildResult result = packageBuilder.build(job(ExportScope.CASE), List.of(message));

        assertThat(result.packageBytes()).isNotEmpty();
        assertThat(result.manifest().messageCount()).isEqualTo(1);
        assertThat(result.manifest().attachmentCount()).isEqualTo(1);
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

    /**
     * The report is what makes the package self-describing evidence, so it
     * ships in both a readable and a parseable form and is itself checksummed
     * in the manifest.
     */
    @Test
    void includesTheAuditReportAndChecksumFile() throws IOException {
        MessageDocument message = MessageDocument.builder()
                .id("msg-1")
                .communicationType("EMAIL")
                .sender("alice@example.com")
                .messageTimestamp(Instant.parse("2026-09-01T10:00:00Z"))
                .build();

        PackageBuildResult result = packageBuilder.build(job(ExportScope.CASE), List.of(message));

        List<String> entryNames = listZipEntries(result.packageBytes());
        assertThat(entryNames).contains("audit-report.txt", "audit-report.json", "checksums.sha256");

        assertThat(result.manifest().items())
                .filteredOn(item -> "AUDIT_REPORT".equals(item.type()))
                .extracting(ManifestItem::path)
                .containsExactlyInAnyOrder("audit-report.txt", "audit-report.json");
    }

    /** Every manifest entry must appear in the sha256sum file, and only those. */
    @Test
    void checksumFileListsEveryManifestItem() throws IOException {
        MessageDocument message = MessageDocument.builder()
                .id("msg-1")
                .communicationType("EMAIL")
                .messageTimestamp(Instant.parse("2026-09-01T10:00:00Z"))
                .build();

        PackageBuildResult result = packageBuilder.build(job(ExportScope.CASE), List.of(message));

        String checksums = new String(readZipEntry(result.packageBytes(), "checksums.sha256"));

        for (ManifestItem item : result.manifest().items()) {
            assertThat(checksums).contains(item.sha256() + "  " + item.path());
        }
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
                job(ExportScope.LEGAL_HOLD),
                List.of(message)
        );

        assertThat(result.manifest().messageCount()).isEqualTo(1);
        assertThat(result.manifest().attachmentCount()).isZero();
        assertThat(result.manifest().scope()).isEqualTo("LEGAL_HOLD");
        assertThat(result.manifest().items())
                .filteredOn(item -> "ATTACHMENT".equals(item.type()))
                .isEmpty();

        List<String> entryNames = listZipEntries(result.packageBytes());
        assertThat(entryNames).contains("messages/msg-2.txt");
        assertThat(entryNames).contains("manifest.json");
    }

    @Test
    void emptyEvidenceProducesValidPackageWithZeroCounts() throws IOException {
        PackageBuildResult result = packageBuilder.build(job(ExportScope.CASE), List.of());

        assertThat(result.manifest().messageCount()).isZero();
        assertThat(result.manifest().attachmentCount()).isZero();
        assertThat(result.manifest().items())
                .filteredOn(item -> "MESSAGE".equals(item.type()))
                .isEmpty();

        // The report and the integrity files still ship, so an empty result is
        // auditable rather than an unexplained empty archive.
        List<String> entryNames = listZipEntries(result.packageBytes());
        assertThat(entryNames).containsExactlyInAnyOrder(
                "audit-report.txt",
                "audit-report.json",
                "checksums.sha256",
                "manifest.json"
        );
    }

    private ExportJobDocument job(ExportScope scope) {
        return ExportJobDocument.builder()
                .exportId("export-1")
                .caseId("case-1")
                .scope(scope)
                .requestedBy("investigator@example.com")
                .createdAt(Instant.parse("2026-09-01T09:00:00Z"))
                .build();
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

    private byte[] readZipEntry(byte[] zipBytes, String name) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(name)) {
                    return zis.readAllBytes();
                }
            }
        }
        throw new AssertionError("entry not found in package: " + name);
    }
}
