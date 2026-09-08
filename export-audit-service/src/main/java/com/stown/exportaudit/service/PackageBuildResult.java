package com.stown.exportaudit.service;

import com.stown.exportaudit.domain.ExportManifest;

/**
 * Result of building an export package. Carries the package bytes (so the
 * caller can upload them and compute the package-level checksum), the manifest
 * (so the caller can persist counts and verify later), and the package-level
 * SHA-256.
 */
public record PackageBuildResult(
        byte[] packageBytes,
        ExportManifest manifest,
        String packageSha256
) {
}
