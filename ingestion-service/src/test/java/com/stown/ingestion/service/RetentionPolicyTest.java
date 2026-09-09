package com.stown.ingestion.service;

import com.stown.ingestion.config.RetentionProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetentionPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

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

    private RetentionPolicy policy(RetentionProperties properties) {
        return new RetentionPolicy(properties);
    }

    private RetentionProperties properties(Map<String, Duration> periods) {
        RetentionProperties properties = new RetentionProperties();
        properties.setPeriods(new LinkedHashMap<>(periods));
        return properties;
    }
}
