package com.stown.search.api;

import java.util.List;

public record SearchResultItem(
        String messageId,
        Double score,
        String communicationType,
        String sender,
        List<String> recipients,
        String subject,
        /**
         * The subject with matches wrapped in {@code <em>}, or null when the
         * subject did not match. Kept separate from {@link #subject} so a
         * client can render the marked-up form without having to strip the
         * markers to get the plain one.
         */
        String subjectHighlight,
        /**
         * A fragment of the body with matches wrapped in {@code <em>},
         * falling back to the opening of the body when nothing in it
         * matched. Never carries the subject: a result list shows the two in
         * different places, and merging them left the body column showing a
         * duplicate of the title.
         */
        String snippet,
        String threadId,
        String messageTimestamp,
        int attachmentCount,
        boolean onHold,
        String dispositionStatus
) {
}
