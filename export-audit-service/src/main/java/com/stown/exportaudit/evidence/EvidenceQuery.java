package com.stown.exportaudit.evidence;

import com.stown.exportaudit.domain.ExportScope;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Filter criteria for an evidence query.
 *
 * <p>For the Case & Hold integration:
 * <ul>
 *   <li>{@code scope=CASE} with {@code caseId}: calls Case & Hold to get
 *       communication IDs, then fetches those messages from MongoDB.</li>
 *   <li>{@code scope=LEGAL_HOLD} with {@code holdId}: calls Case & Hold to get
 *       hold communication IDs, then fetches those messages from MongoDB.</li>
 * </ul>
 */
@Data
@Builder
public class EvidenceQuery {

    private ExportScope scope;
    private String caseId;
    private String holdId;
    private String communicationType;
    private String sender;
    private String threadId;
    private Instant fromTimestamp;
    private Instant toTimestamp;
}
