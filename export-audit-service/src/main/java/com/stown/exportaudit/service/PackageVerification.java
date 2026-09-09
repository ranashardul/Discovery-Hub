package com.stown.exportaudit.service;

import java.util.List;

/**
 * Full verification result for an export package. Reports both the
 * package-level checksum comparison and the per-item comparison against the
 * manifest. {@code verified} is {@code true} only when both the package-level
 * checksum and every per-item checksum match.
 */
public record PackageVerification(
        String exportId,
        boolean packageChecksumMatches,
        String recordedPackageSha256,
        String recomputedPackageSha256,
        List<ItemVerification> items,
        boolean verified
) {
}
