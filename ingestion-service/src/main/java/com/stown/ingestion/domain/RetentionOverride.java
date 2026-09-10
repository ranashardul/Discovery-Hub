package com.stown.ingestion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A retention period set through the API, overriding the configured default
 * for one communication type.
 *
 * <p>Persisted rather than held in memory so a restart does not silently
 * revert a policy someone deliberately changed — a retention period decides
 * when data is destroyed, and quietly reverting to a longer one would keep
 * material past its lawful life while quietly reverting to a shorter one would
 * destroy material early.
 *
 * <p>The period is stored as an ISO-8601 duration string rather than a number
 * of seconds, so what is in the database reads the same way as the
 * configuration it overrides.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "retention_overrides")
public class RetentionOverride {

    /** Communication type, upper-cased, or {@code DEFAULT} for the fallback. */
    @Id
    private String communicationType;

    /** ISO-8601 duration, for example {@code PT720H}. */
    private String period;

    private String updatedBy;

    private Instant updatedAt;
}
