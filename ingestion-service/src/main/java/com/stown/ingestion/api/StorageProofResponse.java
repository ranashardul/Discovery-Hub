package com.stown.ingestion.api;

import java.time.Instant;
import java.util.List;

/**
 * Whether a message's attachment binaries are still in object storage.
 *
 * <p>Exists because "the message is gone" is only half of disposition. The
 * document leaving MongoDB is visible through every other API; the objects
 * leaving S3 is not, and an implementation that deleted the document and
 * orphaned the binaries would look identical from the outside. This reports
 * each key and whether the object answers, so the deletion can be shown
 * rather than asserted.
 *
 * <p>After disposition the keys come from the append-only
 * {@code disposition_audit} record, which is written before the document is
 * removed precisely so the binaries stay traceable. That is what makes this
 * answerable for a message that no longer exists.
 *
 * <p>Deliberately carries no "fully disposed" convenience flag. Jackson
 * serialises a record's components and not its other methods, so such a
 * helper would be absent from the JSON while looking present in Java — and a
 * client reading undefined would conclude the opposite of the truth. The
 * caller derives it from the fields below.
 *
 * @param messageId    the message asked about
 * @param messagePresent whether the document is still in MongoDB
 * @param source       {@code MESSAGE} while it exists, {@code DISPOSITION_AUDIT}
 *                     once it has been disposed, {@code UNKNOWN} when neither
 *                     has a record of it
 * @param disposedAt   when disposition completed, if it has
 * @param objects      one entry per attachment binary
 */
public record StorageProofResponse(
        String messageId,
        boolean messagePresent,
        String source,
        String bucket,
        Instant disposedAt,
        List<StoredObject> objects
) {

    public record StoredObject(String key, boolean present) {
    }
}
