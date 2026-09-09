package com.stown.ingestion.service;

import com.stown.ingestion.config.RetentionProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves the retention period for a communication type and turns it into the
 * absolute expiry stored on the message.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionPolicy {

    private final RetentionProperties properties;

    /**
     * Logs the effective policy and refuses to start with a period below the
     * configured floor. A one-minute retention is a demo setting; reaching
     * production with it would destroy archived data on the next run.
     */
    @PostConstruct
    void validateAndLog() {
        Map<String, Duration> effective = properties.getPeriods().entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> entry.getKey().toUpperCase(Locale.ROOT),
                        Map.Entry::getValue,
                        (first, second) -> second,
                        java.util.LinkedHashMap::new
                ));

        log.info(
                "Retention policy enabled={} dryRun={} default={} periods={} interval={}ms"
                        + " batchSize={} maxDeletesPerRun={}",
                properties.isEnabled(),
                properties.isDryRun(),
                properties.getDefaultPeriod(),
                effective,
                properties.getDispositionIntervalMs(),
                properties.getBatchSize(),
                properties.getMaxDeletesPerRun()
        );

        if (properties.isAllowShortRetention()) {
            log.warn(
                    "Short retention periods are permitted (allow-short-retention=true)."
                            + " This is a demo setting and must not be used with real data."
            );
            return;
        }

        Duration floor = properties.getMinPeriod();

        effective.forEach((type, period) -> reject(type, period, floor));
        reject("default", properties.getDefaultPeriod(), floor);
    }

    private void reject(String label, Duration period, Duration floor) {
        if (period != null && period.compareTo(floor) < 0) {
            throw new IllegalStateException(
                    "Retention period for %s is %s, below the configured floor of %s."
                            .formatted(label, period, floor)
                            + " Set app.retention.allow-short-retention=true to permit it."
            );
        }
    }

    /** Retention period for a communication type, falling back to the default. */
    public Duration resolve(String communicationType) {
        if (communicationType == null || communicationType.isBlank()) {
            return properties.getDefaultPeriod();
        }

        return properties.getPeriods().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(communicationType.trim()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(properties.getDefaultPeriod());
    }

    /** Absolute expiry for a message created at {@code createdAt}. */
    public Instant expiryFor(String communicationType, Instant createdAt) {
        return createdAt.plus(resolve(communicationType));
    }
}
