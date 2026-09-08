package com.stown.exportaudit.domain;

import java.time.Instant;
import java.util.List;

/**
 * Manifest written into the export package as {@code manifest.json}. It lists
 * every item with its SHA-256 checksum and records the package-level checksum,
 * so the package can be verified later without a separate out-of-band record.
 *
 * @param exportId       job the package belongs to
 * @param caseId         case the evidence was exported for
 * @param scope          export scope
 * @param requestedBy    actor that requested the export
 * @param createdAt      package creation time
 * @param packageSha256  SHA-256 of the package bytes (the ZIP itself)
 * @param messageCount   number of messages in the package
 * @param attachmentCount number of attachments in the package
 * @param items          per-item entries with paths and checksums
 */
public record ExportManifest(
        String exportId,
        String caseId,
        String scope,
        String requestedBy,
        Instant createdAt,
        String packageSha256,
        int messageCount,
        int attachmentCount,
        List<ManifestItem> items
) {
}
