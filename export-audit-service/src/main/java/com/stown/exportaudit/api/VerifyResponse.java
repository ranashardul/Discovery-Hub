package com.stown.exportaudit.api;

import com.stown.exportaudit.service.ItemVerification;

import java.util.List;

/**
 * Response for the verify endpoint. Reports the package-level checksum
 * comparison and the per-item comparison against the manifest, so any
 * tampering is visible per item. {@code verified} is {@code true} only when
 * both the package-level checksum and every per-item checksum match.
 */
public record VerifyResponse(
        String exportId,
        boolean verified,
        boolean packageChecksumMatches,
        String recordedPackageSha256,
        String recomputedPackageSha256,
        List<ItemVerification> items
) {
}
