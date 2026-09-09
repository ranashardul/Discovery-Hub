package com.stown.exportaudit.domain;

/**
 * A single entry in the export manifest, carrying the item's location inside
 * the package and its SHA-256 checksum for verification.
 *
 * @param type         {@code MESSAGE} or {@code ATTACHMENT}
 * @param path         path of the item inside the export package
 * @param messageId    owning message (set for attachments, equals the item id
 *                     for messages)
 * @param attachmentId attachment identifier, or {@code null} for messages
 * @param filename     attachment filename, or {@code null} for messages
 * @param sizeBytes    byte size of the item
 * @param sha256       SHA-256 of the item bytes
 */
public record ManifestItem(
        String type,
        String path,
        String messageId,
        String attachmentId,
        String filename,
        long sizeBytes,
        String sha256
) {
}
