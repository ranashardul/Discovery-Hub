package com.stown.ingestion.service;

import com.stown.ingestion.config.RetentionProperties;
import com.stown.ingestion.domain.RetentionOverride;
import com.stown.ingestion.repository.RetentionOverrideRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetentionPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    private final AuditPublisher auditPublisher = mock(AuditPublisher.class);

    @Test
    void resolvesThePeriodConfiguredForEachCommunicationType() {
        RetentionPolicy policy = policy(properties(Map.of(
                "EMAIL", Duration.ofDays(2555),
                "CHAT", Duration.ofDays(1095)
        )));

        assertThat(policy.resolve("EMAIL")).isEqualTo(Duration.ofDays(2555));
        assertThat(policy.resolve("CHAT")).isEqualTo(Duration.ofDays(1095));
    }

    @Test
    void matchesTheCommunicationTypeIgnoringCaseAndWhitespace() {
        RetentionPolicy policy = policy(properties(Map.of("EMAIL", Duration.ofDays(30))));

        assertThat(policy.resolve("email")).isEqualTo(Duration.ofDays(30));
        assertThat(policy.resolve("  Email  ")).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void fallsBackToTheDefaultForUnknownOrMissingTypes() {
        RetentionProperties properties = properties(Map.of("EMAIL", Duration.ofDays(30)));
        properties.setDefaultPeriod(Duration.ofDays(90));

        RetentionPolicy policy = policy(properties);

        assertThat(policy.resolve("VOICEMAIL")).isEqualTo(Duration.ofDays(90));
        assertThat(policy.resolve(null)).isEqualTo(Duration.ofDays(90));
        assertThat(policy.resolve("  ")).isEqualTo(Duration.ofDays(90));
    }

    @Test
    void computesExpiryFromCreationTime() {
        RetentionPolicy policy = policy(properties(Map.of("EMAIL", Duration.ofMinutes(1))));

        assertThat(policy.expiryFor("EMAIL", NOW))
                .isEqualTo(Instant.parse("2026-09-08T10:01:00Z"));
    }

    @Test
    void acceptsAOneMinutePeriodWhichIsWhatTheDemoRequires() {
        RetentionProperties properties = properties(Map.of("EMAIL", Duration.ofMinutes(1)));
        properties.setAllowShortRetention(true);

        RetentionPolicy policy = policy(properties);

        assertThat(policy.resolve("EMAIL")).isEqualTo(Duration.ofSeconds(60));
        assertThatCode(policy::validateAndLog).doesNotThrowAnyException();
    }

    @Test
    void refusesToStartWhenAPeriodIsBelowTheFloorAndShortRetentionIsNotAllowed() {
        RetentionProperties properties = properties(Map.of("EMAIL", Duration.ofMinutes(1)));
        properties.setMinPeriod(Duration.ofHours(24));
        properties.setAllowShortRetention(false);

        assertThatThrownBy(() -> policy(properties).validateAndLog())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMAIL")
                .hasMessageContaining("allow-short-retention");
    }

    @Test
    void refusesAShortDefaultPeriodToo() {
        RetentionProperties properties = properties(Map.of());
        properties.setDefaultPeriod(Duration.ofMinutes(5));
        properties.setMinPeriod(Duration.ofHours(24));

        assertThatThrownBy(() -> policy(properties).validateAndLog())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default");
    }

    @Test
    void allowsPeriodsAtOrAboveTheFloor() {
        RetentionProperties properties = properties(Map.of("EMAIL", Duration.ofHours(24)));
        properties.setMinPeriod(Duration.ofHours(24));

        assertThatCode(() -> policy(properties).validateAndLog()).doesNotThrowAnyException();
    }

    // ------------------------------------------------------- policy overrides

    @Test
    void anOverrideWinsOverTheConfiguredPeriod() {
        RetentionPolicy policy = policy(properties(Map.of("EMAIL", Duration.ofDays(2555))));

        policy.setPeriod("EMAIL", Duration.ofDays(30), "asritha");

        assertThat(policy.resolve("EMAIL")).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void anOverrideSurvivesLookupByAnyCasing() {
        RetentionPolicy policy = policy(properties(Map.of("EMAIL", Duration.ofDays(2555))));

        policy.setPeriod("email", Duration.ofDays(30), "asritha");

        assertThat(policy.resolve("EMAIL")).isEqualTo(Duration.ofDays(30));
        assertThat(policy.resolve("  email ")).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void theConfiguredPeriodRemainsTheFallbackForTypesWithNoOverride() {
        RetentionPolicy policy = policy(properties(Map.of(
                "EMAIL", Duration.ofDays(2555),
                "CHAT", Duration.ofDays(1095)
        )));

        policy.setPeriod("EMAIL", Duration.ofDays(30), "asritha");

        assertThat(policy.resolve("CHAT")).isEqualTo(Duration.ofDays(1095));
    }

    @Test
    void theDefaultPeriodCanBeOverriddenToo() {
        RetentionProperties properties = properties(Map.of());
        properties.setDefaultPeriod(Duration.ofDays(2555));
        RetentionPolicy policy = policy(properties);

        policy.setPeriod(null, Duration.ofDays(10), "asritha");

        assertThat(policy.resolve(null)).isEqualTo(Duration.ofDays(10));
        assertThat(policy.resolve("VOICEMAIL")).isEqualTo(Duration.ofDays(10));
    }

    /**
     * The floor exists to keep a demo value away from real data, and an HTTP
     * endpoint is exactly how one would get there. Shortening a period deletes
     * archived material on the next disposition run.
     */
    @Test
    void refusesAnOverrideBelowTheFloor() {
        RetentionProperties properties = properties(Map.of("EMAIL", Duration.ofDays(2555)));
        properties.setMinPeriod(Duration.ofHours(24));
        properties.setAllowShortRetention(false);

        RetentionPolicy policy = policy(properties);

        assertThatThrownBy(() -> policy.setPeriod("EMAIL", Duration.ofMinutes(5), "asritha"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allow-short-retention");

        assertThat(policy.resolve("EMAIL")).isEqualTo(Duration.ofDays(2555));
    }

    @Test
    void permitsAnOverrideBelowTheFloorWhenShortRetentionIsAllowed() {
        RetentionProperties properties = properties(Map.of("EMAIL", Duration.ofDays(2555)));
        properties.setMinPeriod(Duration.ofHours(24));
        properties.setAllowShortRetention(true);

        RetentionPolicy policy = policy(properties);
        policy.setPeriod("EMAIL", Duration.ofMinutes(5), "asritha");

        assertThat(policy.resolve("EMAIL")).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void refusesAZeroOrNegativePeriod() {
        RetentionPolicy policy = policy(properties(Map.of()));

        assertThatThrownBy(() -> policy.setPeriod("EMAIL", Duration.ZERO, "asritha"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.setPeriod("EMAIL", Duration.ofMinutes(-5), "asritha"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Shortening a period destroys archived material, so the change is a
     * chain-of-custody event carrying what it was and what it became.
     */
    @Test
    void publishesAnAuditEventCarryingTheBeforeAndAfterPeriods() {
        RetentionPolicy policy = policy(properties(Map.of("EMAIL", Duration.ofDays(2555))));

        policy.setPeriod("EMAIL", Duration.ofDays(30), "asritha");

        verify(auditPublisher).retentionPolicyChanged(
                "EMAIL",
                Duration.ofDays(2555),
                Duration.ofDays(30),
                "asritha"
        );
    }

    @Test
    void reportsEveryEffectivePeriodIncludingTheDefault() {
        RetentionProperties properties = properties(Map.of(
                "EMAIL", Duration.ofDays(2555),
                "CHAT", Duration.ofDays(1095)
        ));
        properties.setDefaultPeriod(Duration.ofDays(90));

        RetentionPolicy policy = policy(properties);
        policy.setPeriod("CHAT", Duration.ofDays(7), "asritha");

        assertThat(policy.effectivePeriods())
                .containsEntry("EMAIL", Duration.ofDays(2555))
                .containsEntry("CHAT", Duration.ofDays(7))
                .containsEntry(RetentionPolicy.DEFAULT_KEY, Duration.ofDays(90));
    }

    /**
     * Resolution is on the ingestion write path, so an unreachable override
     * store must not stop messages being archived: it falls back to the
     * configured period rather than failing the request.
     */
    @Test
    void fallsBackToConfigurationWhenTheOverrideStoreFails() {
        RetentionOverrideRepository failing = mock(RetentionOverrideRepository.class);
        when(failing.findById(anyString())).thenThrow(new RuntimeException("mongo unreachable"));

        RetentionProperties properties = properties(Map.of("EMAIL", Duration.ofDays(2555)));
        RetentionPolicy policy = new RetentionPolicy(properties, failing, auditPublisher);

        assertThat(policy.resolve("EMAIL")).isEqualTo(Duration.ofDays(2555));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * In-memory stand-in for the override store, so the tests exercise the
     * real layering rather than a mock that returns whatever they ask for.
     */
    private RetentionOverrideRepository overrides() {
        Map<String, RetentionOverride> store = new LinkedHashMap<>();
        RetentionOverrideRepository repository = mock(RetentionOverrideRepository.class);

        when(repository.save(any(RetentionOverride.class))).thenAnswer(invocation -> {
            RetentionOverride saved = invocation.getArgument(0);
            store.put(saved.getCommunicationType(), saved);
            return saved;
        });
        when(repository.findById(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(store.get(invocation.getArgument(0))));
        when(repository.findAll()).thenAnswer(invocation -> List.copyOf(store.values()));

        return repository;
    }

    private RetentionPolicy policy(RetentionProperties properties) {
        return new RetentionPolicy(properties, overrides(), auditPublisher);
    }

    private RetentionProperties properties(Map<String, Duration> periods) {
        RetentionProperties properties = new RetentionProperties();
        properties.setPeriods(new LinkedHashMap<>(periods));
        return properties;
    }
}
