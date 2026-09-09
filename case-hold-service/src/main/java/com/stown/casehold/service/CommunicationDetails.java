package com.stown.casehold.service;

import com.stown.casehold.api.CommunicationDetail;
import com.stown.casehold.domain.MessageDocument;

/**
 * Maps the read-only {@link MessageDocument} projection onto the API's
 * {@link CommunicationDetail}. Kept out of the records themselves so the API
 * package carries no dependency on the MongoDB mapping.
 */
final class CommunicationDetails {

    private CommunicationDetails() {
    }

    /**
     * Returns resolved detail for a message, or the unresolved marker when the
     * reference matched nothing.
     */
    static CommunicationDetail from(MessageDocument message) {
        if (message == null) {
            return CommunicationDetail.unresolved();
        }

        return new CommunicationDetail(
                true,
                message.getSender(),
                message.getRecipients(),
                message.getSubject(),
                message.getMessageTimestamp(),
                message.getThreadId(),
                message.getAttachments() == null ? 0 : message.getAttachments().size(),
                message.getHoldCount(),
                message.getDispositionStatus(),
                message.getRetentionUntil()
        );
    }
}
