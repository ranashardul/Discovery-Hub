package com.stown.exportaudit.service;

import com.stown.exportaudit.domain.AttachmentMetadata;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackageVerifierTest {

    @Mock
    private S3StorageService storageService;

    private ChecksumService checksumService;
    private PackageBuilder packageBuilder;
    private PackageVerifier packageVerifier;

    @BeforeEach
    void setUp() {
        checksumService = new ChecksumService();
        ObjectMapper objectMapper = new ObjectMapper();
        packageBuilder = new PackageBuilder(storageService, checksumService, objectMapper);
        packageVerifier = new PackageVerifier(checksumService, objectMapper);
    }

    @Test
    void verifiesUntamperedPackage() throws IOException {
        byte[] packageBytes = buildPackageWithMessage("msg-1", "Hello world");

        PackageVerification result = packageVerifier.verify(
                "export-1", packageBytes, checksumService.sha256Hex(packageBytes)
        );

        assertThat(result.verified()).isTrue();
        assertThat(result.packageChecksumMatches()).isTrue();
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).matches()).isTrue();
    }

    @Test
    void detectsTamperedMessageItem() throws IOException {
        byte[] packageBytes = buildPackageWithMessage("msg-1", "Hello world");

        // Tamper: replace the message content inside the ZIP.
        byte[] tampered = replaceEntry(packageBytes, "messages/msg-1.txt",
                "Tampered content".getBytes());

        PackageVerification result = packageVerifier.verify(
                "export-1", tampered, checksumService.sha256Hex(packageBytes)
        );

        assertThat(result.verified()).isFalse();
        assertThat(result.packageChecksumMatches()).isFalse();

        ItemVerification item = result.items().stream()
                .filter(i -> "MESSAGE".equals(i.item().type()))
                .findFirst().orElseThrow();
        assertThat(item.matches()).isFalse();
        assertThat(item.recomputedSha256()).isNotEqualTo(item.item().sha256());
    }

    @Test
    void detectsMissingEntry() throws IOException {
        byte[] packageBytes = buildPackageWithMessage("msg-1", "Hello world");

        // Tamper: rebuild the ZIP without the message entry.
        byte[] tampered = removeEntry(packageBytes, "messages/msg-1.txt");

        PackageVerification result = packageVerifier.verify(
                "export-1", tampered, checksumService.sha256Hex(packageBytes)
        );

        assertThat(result.verified()).isFalse();

        ItemVerification item = result.items().get(0);
        assertThat(item.matches()).isFalse();
        assertThat(item.recomputedSha256()).isNull();
    }

    @Test
    void verifiesPackageWithAttachment() throws IOException {
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
                .communicationType("EMAIL")
                .sender("alice@example.com")
                .recipients(List.of("bob@example.com"))
                .subject("Subject")
                .body("Body")
                .messageTimestamp(Instant.parse("2026-09-01T10:00:00Z"))
                .attachments(List.of(attachment))
                .build();

        when(storageService.readAttachment("source-bucket",
                "messages/msg-1/attachments/att-1/report.pdf"))
                .thenReturn(attachmentContent);

        PackageBuildResult built = packageBuilder.build(
                "export-1", "case-1", null, "investigator@example.com",
                List.of(message)
        );

        PackageVerification result = packageVerifier.verify(
                "export-1", built.packageBytes(), built.packageSha256()
        );

        assertThat(result.verified()).isTrue();
        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).allMatch(ItemVerification::matches);
    }

    @Test
    void emptyPackageVerifiesCleanly() throws IOException {
        byte[] packageBytes = buildPackageWithMessage(null, null);

        PackageVerification result = packageVerifier.verify(
                "export-1", packageBytes, checksumService.sha256Hex(packageBytes)
        );

        assertThat(result.verified()).isTrue();
        assertThat(result.items()).isEmpty();
    }

    private byte[] buildPackageWithMessage(String messageId, String body) throws IOException {
        List<MessageDocument> messages = new ArrayList<>();

        if (messageId != null) {
            messages.add(MessageDocument.builder()
                    .id(messageId)
                    .deduplicationKey("dedup-" + messageId)
                    .communicationType("EMAIL")
                    .sender("alice@example.com")
                    .recipients(List.of("bob@example.com"))
                    .subject("Subject")
                    .body(body)
                    .messageTimestamp(Instant.parse("2026-09-01T10:00:00Z"))
                    .build());
        }

        PackageBuildResult result = packageBuilder.build(
                "export-1", "case-1", null, "investigator@example.com",
                messages
        );

        return result.packageBytes();
    }

    private byte[] replaceEntry(byte[] zipBytes, String entryName, byte[] newContent) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes));
             ZipOutputStream zos = new ZipOutputStream(out)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                zos.putNextEntry(new ZipEntry(entry.getName()));
                if (entry.getName().equals(entryName)) {
                    zos.write(newContent);
                } else {
                    zos.write(zis.readAllBytes());
                }
                zos.closeEntry();
            }
        }

        return out.toByteArray();
    }

    private byte[] removeEntry(byte[] zipBytes, String entryToRemove) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes));
             ZipOutputStream zos = new ZipOutputStream(out)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(entryToRemove)) {
                    continue;
                }
                zos.putNextEntry(new ZipEntry(entry.getName()));
                zos.write(zis.readAllBytes());
                zos.closeEntry();
            }
        }

        return out.toByteArray();
    }
}
