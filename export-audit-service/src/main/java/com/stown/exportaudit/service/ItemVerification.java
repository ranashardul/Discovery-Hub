package com.stown.exportaudit.service;

import com.stown.exportaudit.domain.ManifestItem;

/**
 * Per-item verification result: the manifest entry, the recomputed checksum,
 * and whether it matched the manifest's recorded checksum.
 */
public record ItemVerification(
        ManifestItem item,
        String recomputedSha256,
        boolean matches
) {
}
