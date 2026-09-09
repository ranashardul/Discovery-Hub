package com.stown.ingestion.domain;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.mapping.Document;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the fields other services read out of {@code legal_discovery.messages}.
 *
 * <p>search-service keeps its own copy of this model with no compile-time link
 * to ours, so renaming or retyping a field here produces no compile error
 * there: it silently reads {@code null}. That is documented in AGENTS.md as a
 * standing hazard, and this test turns it into a build failure on our side.
 *
 * <p>Adding fields is safe and deliberately not restricted. Only the shape
 * consumers already depend on is pinned.
 */
class MessageContractTest {

    /** Field name to type, as read by search-service. */
    private static final Map<String, Class<?>> CONSUMED_FIELDS = Map.ofEntries(
            Map.entry("id", String.class),
            Map.entry("deduplicationKey", String.class),
            Map.entry("externalMessageId", String.class),
            Map.entry("communicationType", String.class),
            Map.entry("sender", String.class),
            Map.entry("recipients", List.class),
            Map.entry("subject", String.class),
            Map.entry("body", String.class),
            Map.entry("messageTimestamp", Instant.class),
            Map.entry("threadId", String.class),
            Map.entry("attachments", List.class),
            Map.entry("createdAt", Instant.class),
            // Projected into the search index and filtered on by onHold.
            Map.entry("holdCount", int.class),
            Map.entry("dispositionStatus", String.class)
    );

    @Test
    void keepsEveryFieldThatSearchServiceReads() {
        CONSUMED_FIELDS.forEach((name, type) -> {
            Field field = findField(name);

            assertThat(field)
                    .describedAs(
                            "MessageDocument.%s is read by search-service; renaming or"
                                    + " removing it makes that service silently read null",
                            name
                    )
                    .isNotNull();

            assertThat(field.getType())
                    .describedAs(
                            "MessageDocument.%s must stay a %s for search-service to bind it",
                            name,
                            type.getSimpleName()
                    )
                    .isEqualTo(type);
        });
    }

    @Test
    void keepsHoldCountAnIntSoTheOnHoldFilterKeepsWorking() {
        Field holdCount = findField("holdCount");

        // An enum or Boolean here would break the numeric comparison the
        // search service uses for onHold.
        assertThat(holdCount.getType()).isEqualTo(int.class);
    }

    @Test
    void keepsDispositionStatusAStringBecauseItIsIndexedAsAKeyword() {
        Field status = findField("dispositionStatus");

        // Persisting an enum would still write a string, but the Java type is
        // pinned so the two copies of the model cannot drift.
        assertThat(status.getType()).isEqualTo(String.class);
        assertThat(DispositionStatus.ACTIVE).isEqualTo("ACTIVE");
        assertThat(DispositionStatus.ON_HOLD).isEqualTo("ON_HOLD");
    }

    @Test
    void staysMappedToTheMessagesCollection() {
        Document mapping = MessageDocument.class.getAnnotation(Document.class);

        assertThat(mapping).isNotNull();
        assertThat(mapping.collection())
                .describedAs("search-service reads the messages collection directly")
                .isEqualTo("messages");
    }

    private Field findField(String name) {
        try {
            return MessageDocument.class.getDeclaredField(name);
        } catch (NoSuchFieldException exception) {
            return null;
        }
    }
}
