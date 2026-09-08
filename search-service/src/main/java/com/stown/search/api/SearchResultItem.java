package com.stown.search.api;

import java.util.List;

public record SearchResultItem(
        String messageId,
        Double score,
        String communicationType,
        String sender,
        List<String> recipients,
        String subject,
        String snippet,
        String threadId,
        String messageTimestamp,
        int attachmentCount
) {
}
