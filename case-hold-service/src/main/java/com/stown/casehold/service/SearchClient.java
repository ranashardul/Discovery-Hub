package com.stown.casehold.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.stown.casehold.domain.HoldCriteria;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.util.List;

/**
 * Counts what a hold rule would cover, by asking the search service.
 *
 * <p>Deliberately delegates rather than querying MongoDB directly. This
 * service can read the message collection — {@link MessageLookupService} does,
 * to resolve references — so a local count was possible. But the hold that
 * follows a preview is applied by ingestion resolving the same rule, and the
 * reviewer then confirms it through the search index. Three implementations of
 * "which messages match this rule" would drift, and a preview that disagreed
 * with the hold it previewed is worse than no preview.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchClient {

    private final RestClient searchRestClient;

    /**
     * How many messages the criteria currently match.
     *
     * <p>Asks for the smallest page the search API allows and reads the total.
     * {@code size=0} would be the natural way to count without transferring
     * documents, but the search API validates {@code size} as at least 1 and
     * answers 400. The total is exact regardless, because that endpoint tracks
     * total hits; one document on the wire is the whole cost of not adding a
     * dedicated count endpoint.
     *
     * <p>Advisory by nature: the count is a snapshot, and the hold is applied
     * later against whatever matches then. A failure is reported to the caller
     * rather than swallowed, because silently showing "0 messages" would read
     * as "this rule matches nothing" and talk a reviewer out of a hold they
     * needed.
     */
    public long countMatching(HoldCriteria criteria) {
        SearchTotal response = searchRestClient.get()
                .uri(builder -> {
                    UriBuilder uri = builder.path("/api/search").queryParam("size", 1);

                    // A participant matches either side of a conversation, so
                    // the search API's dedicated participant filter is used
                    // rather than the free-text query, which covers only
                    // subject and body and would match none of them. Repeated
                    // values are an OR there, matching what the hold rule
                    // means and what ingestion applies.
                    List<String> participants = criteria.getParticipants();
                    if (participants != null && !participants.isEmpty()) {
                        participants.forEach(participant -> uri.queryParam("participant", participant));
                    }

                    List<String> types = criteria.getCommunicationTypes();
                    if (types != null && types.size() == 1) {
                        uri.queryParam("communicationType", types.getFirst());
                    }

                    if (criteria.getDateFrom() != null) {
                        uri.queryParam("after", criteria.getDateFrom().toString());
                    }
                    if (criteria.getDateTo() != null) {
                        uri.queryParam("before", criteria.getDateTo().toString());
                    }

                    return uri.build();
                })
                .retrieve()
                .body(SearchTotal.class);

        long total = response == null ? 0L : response.total();

        log.info("Hold scope preview matched {} message(s)", total);

        return total;
    }

    /**
     * Only the total is read. Declared locally and tolerant of unknown fields
     * so a new field on the search response cannot break the preview.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SearchTotal(long total) {
    }
}
