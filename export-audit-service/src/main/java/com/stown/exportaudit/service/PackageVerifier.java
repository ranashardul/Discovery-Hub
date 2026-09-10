package com.stown.exportaudit.service;

import com.stown.exportaudit.domain.ExportManifest;
import com.stown.exportaudit.domain.ManifestItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Verifies an export package against its manifest. Parses the ZIP, reads
 * {@code manifest.json}, recomputes the SHA-256 of every item and the
 * package-level checksum, and compares each against the recorded value. Any
 * tampering with an item, a missing entry, or a changed package byte is
 * detected and reported.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackageVerifier {

    private static final String MANIFEST_ENTRY = "manifest.json";

    private final ChecksumService checksumService;
    private final ObjectMapper objectMapper;

    /**
     * Verifies a package. The {@code recordedPackageSha256} is the value
     * persisted on the export job at completion time; it is compared against a
     * fresh computation over {@code packageBytes} as a defence-in-depth check
     * in addition to the per-item manifest comparison.
     */
    public PackageVerification verify(
            String exportId,
            byte[] packageBytes,
            String recordedPackageSha256
    ) throws IOException {
        Map<String, byte[]> entries = readEntries(packageBytes);
        ExportManifest manifest = readManifest(entries);

        List<ItemVerification> itemResults = new ArrayList<>();

        for (ManifestItem item : manifest.items()) {
            byte[] content = entries.get(item.path());
            ItemVerification result = verifyItem(item, content);
            itemResults.add(result);
        }

        String recomputedPackageSha256 = checksumService.sha256Hex(packageBytes);
        boolean packageChecksumMatches =
                recordedPackageSha256 != null && recordedPackageSha256.equals(recomputedPackageSha256);

        boolean allItemsMatch = itemResults.stream().allMatch(ItemVerification::matches);
        boolean verified = packageChecksumMatches && allItemsMatch;

        log.info(
                "Verified package exportId={} packageMatches={} itemsMatch={}/{} verified={}",
                exportId,
                packageChecksumMatches,
                itemResults.stream().filter(ItemVerification::matches).count(),
                itemResults.size(),
                verified
        );

        return new PackageVerification(
                exportId,
                packageChecksumMatches,
                recordedPackageSha256,
                recomputedPackageSha256,
                itemResults,
                verified
        );
    }

    private ItemVerification verifyItem(ManifestItem item, byte[] content) {
        if (content == null) {
            return new ItemVerification(item, null, false);
        }

        String recomputed = checksumService.sha256Hex(content);
        boolean matches = recomputed.equals(item.sha256());

        return new ItemVerification(item, recomputed, matches);
    }

    /**
     * Reads the manifest out of a package without verifying it.
     *
     * <p>The manifest is only ever written inside the ZIP, deliberately: an
     * evidence package that carried its own inventory in a separate database
     * row could be verified against a record that had itself been altered.
     * Reading it back therefore means fetching the package, which is why this
     * is a distinct call from {@link #verify} rather than a field on the job.
     */
    public ExportManifest readManifest(byte[] packageBytes) throws IOException {
        return readManifest(readEntries(packageBytes));
    }

    private Map<String, byte[]> readEntries(byte[] packageBytes) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(packageBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }

        return entries;
    }

    private ExportManifest readManifest(Map<String, byte[]> entries) throws IOException {
        byte[] manifestBytes = entries.get(MANIFEST_ENTRY);

        if (manifestBytes == null) {
            throw new ExportProcessingException(
                    "Package is missing manifest.json; cannot verify",
                    null
            );
        }

        return objectMapper.readValue(manifestBytes, ExportManifest.class);
    }
}
