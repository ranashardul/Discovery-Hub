package com.stown.exportaudit.service;

import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Standalone SHA-256 helper used by the package builder for per-item and
 * package-level checksums. {@code S3StorageService} keeps its own copy for the
 * read path so the two stay decoupled.
 */
@Service
public class ChecksumService {

    public String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }
}
