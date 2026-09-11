package com.stown.ingestion.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Retention and disposition policy.
 *
 * <p>Periods are configured per communication type, because regulated firms
 * retain email and chat for different lengths of time. Values bind as Spring
 * {@link Duration}, so {@code 2555d} and {@code 1m} are both valid and a demo
 * needs no code change.
 */
@Data
@ConfigurationProperties(prefix = "app.retention")
public class RetentionProperties {

    /** Master switch for the scheduled disposition job. */
    private boolean enabled = false;

    /**
     * Evaluate and record candidates without deleting anything. Useful for
     * rehearsing a policy change before it destroys data.
     */
    private boolean dryRun = false;

    /** Retention period per communication type, keyed case-insensitively. */
    private Map<String, Duration> periods = new LinkedHashMap<>();

    /** Applied when a communication type has no explicit period. */
    private Duration defaultPeriod = Duration.ofDays(2555);

    /**
     * Guard rail against a demo value reaching a real environment. A period
     * below this is refused unless {@link #allowShortRetention} is set.
     */
    private Duration minPeriod = Duration.ofHours(24);

    /** Permits periods below {@link #minPeriod}. Demo only. */
    private boolean allowShortRetention = false;

    /**
     * Whether a single message may carry its own retention period, set on the
     * ingestion request.
     *
     * <p>Separate from {@link #allowShortRetention} because the two have very
     * different blast radii. Shortening a type's period expires every message
     * of that type already in the archive; a per-message period expires one
     * message and cannot touch anything that was ingested before it. That is
     * what makes a retention demo safe to run against a populated archive,
     * and why this is allowed by default while the policy floor is not.
     */
    private boolean messageOverrideEnabled = true;

    /**
     * Longest per-message retention that may be requested. Short by design:
     * this is for demonstrating disposition inside a presentation, not for
     * expressing a retention schedule.
     */
    private Duration messageOverrideMax = Duration.ofMinutes(5);

    /** Documents examined per run. */
    private int batchSize = 200;

    /**
     * Upper bound on deletions in a single run, so a misconfigured period
     * cannot empty the collection in one sweep.
     */
    private int maxDeletesPerRun = 500;

    /** Delay between disposition runs, in milliseconds. */
    private long dispositionIntervalMs = 60_000L;

    /** Delay between purge-sweeper runs, in milliseconds. */
    private long purgeSweepIntervalMs = 60_000L;
}
