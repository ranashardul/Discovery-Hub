package com.stown.ingestion.service;

import com.stown.ingestion.config.RetentionProperties;
import com.stown.ingestion.domain.RetentionOverride;
import com.stown.ingestion.repository.RetentionOverrideRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
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

    /** Key under which the fallback period is stored and reported. */
    public static final String DEFAULT_KEY = "DEFAULT";

    private final RetentionProperties properties;
    private final RetentionOverrideRepository overrideRepository;
    private final AuditPublisher auditPublisher;

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

    /**
     * Retention period for a communication type.
     *
     * <p>Resolution order is override, then configured period, then the
     * default — so a period set through the API wins over the one the
     * environment was started with, and the configuration remains the
     * fallback rather than being replaced.
     */
    public Duration resolve(String communicationType) {
        String type = communicationType == null || communicationType.isBlank()
                ? null
                : communicationType.trim().toUpperCase(Locale.ROOT);

        if (type != null) {
            Duration override = override(type);
            if (override != null) {
                return override;
            }

            Duration configured = properties.getPeriods().entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(type))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);

            if (configured != null) {
                return configured;
            }
        }

        Duration defaultOverride = override(DEFAULT_KEY);
        return defaultOverride != null ? defaultOverride : properties.getDefaultPeriod();
    }

    /**
     * Reads a stored override, degrading to "no override" on any failure.
     *
     * <p>This is on the ingestion write path, so an unreachable database or an
     * unparseable value must not stop messages being archived. Falling back to
     * the configured period keeps ingestion working with the environment's own
     * policy rather than failing the request.
     */
    private Duration override(String key) {
        try {
            return overrideRepository.findById(key)
                    .map(RetentionOverride::getPeriod)
                    .map(Duration::parse)
                    .orElse(null);
        } catch (Exception exception) {
            log.warn(
                    "Ignoring retention override for {} reason={}",
                    key,
                    exception.getMessage()
            );
            return null;
        }
    }

    /** Every effective period, for the policy API. */
    public Map<String, Duration> effectivePeriods() {
        Map<String, Duration> periods = new LinkedHashMap<>();

        properties.getPeriods().keySet().forEach(type -> {
            String key = type.toUpperCase(Locale.ROOT);
            periods.put(key, resolve(key));
        });

        // Any type overridden through the API but absent from configuration
        // still has an effective period and belongs in the answer.
        overrideRepository.findAll().forEach(override -> {
            if (!DEFAULT_KEY.equals(override.getCommunicationType())) {
                periods.putIfAbsent(override.getCommunicationType(), resolve(override.getCommunicationType()));
            }
        });

        periods.put(DEFAULT_KEY, resolve(null));

        return periods;
    }

    /**
     * Sets the retention period for a communication type.
     *
     * <p>The {@code minPeriod} floor is enforced here, not only at startup.
     * The floor exists to stop a demo value reaching real data, and an HTTP
     * endpoint is exactly how that would happen — shortening a period deletes
     * archived material on the next disposition run.
     */
    public Duration setPeriod(String communicationType, Duration period, String updatedBy) {
        String key = communicationType == null || communicationType.isBlank()
                ? DEFAULT_KEY
                : communicationType.trim().toUpperCase(Locale.ROOT);

        if (period == null || period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("A retention period must be a positive duration");
        }

        if (!properties.isAllowShortRetention() && period.compareTo(properties.getMinPeriod()) < 0) {
            throw new IllegalArgumentException(
                    "Retention period %s for %s is below the configured floor of %s;"
                            .formatted(period, key, properties.getMinPeriod())
                            + " set app.retention.allow-short-retention=true to permit it"
            );
        }

        Duration previous = resolve(DEFAULT_KEY.equals(key) ? null : key);

        overrideRepository.save(RetentionOverride.builder()
                .communicationType(key)
                .period(period.toString())
                .updatedBy(updatedBy)
                .updatedAt(Instant.now())
                .build());

        log.warn(
                "Retention period changed type={} {} -> {} by {}",
                key,
                previous,
                period,
                updatedBy
        );

        auditPublisher.retentionPolicyChanged(key, previous, period, updatedBy);

        return period;
    }

    /** Absolute expiry for a message created at {@code createdAt}. */
    public Instant expiryFor(String communicationType, Instant createdAt) {
        return createdAt.plus(resolve(communicationType));
    }
}
