package com.stown.search.service;

import com.stown.search.api.CustodianResponse;
import com.stown.search.api.ResolvedIdsResponse;
import com.stown.search.api.SearchRequest;
import com.stown.search.config.SearchProperties;
import com.stown.search.index.MessageIndexClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the two capabilities added so the UI stops faking them: resolving a
 * criteria set to every matching id, and deriving custodians from the index.
 */
@ExtendWith(MockitoExtension.class)
class SearchServiceResolveTest {

    @Mock
    private MessageIndexClient indexClient;

    @Mock
    private SearchQueryBuilder queryBuilder;

    @Mock
    private IndexingService indexingService;

    private SearchProperties properties;
    private SearchService service;

    @BeforeEach
    void setUp() {
        properties = new SearchProperties();
        properties.setMaxResolvedIds(5);
        properties.setResolveIdsPageSize(2);
        properties.setMaxCustodians(50);

        service = new SearchService(indexClient, queryBuilder, indexingService, properties);
    }

    private SearchCriteria criteria() {
        return SearchCriteria.of(SearchRequest.builder().q("budget").build(), 100);
    }

    @Test
    void returnsEveryMatchingIdWithATotal() throws IOException {
        when(indexClient.searchIds(any(), anyInt(), anyInt()))
                .thenReturn(List.of("m-1", "m-2", "m-3"));

        ResolvedIdsResponse response = service.resolveIds(criteria());

        assertThat(response.messageIds()).containsExactly("m-1", "m-2", "m-3");
        assertThat(response.total()).isEqualTo(3);
        assertThat(response.truncated()).isFalse();
    }

    /** The cap and page size are configuration, not hardcoded in the client. */
    @Test
    void passesTheConfiguredCapAndPageSizeToTheIndex() throws IOException {
        when(indexClient.searchIds(any(), anyInt(), anyInt())).thenReturn(List.of());

        service.resolveIds(criteria());

        verify(indexClient).searchIds(any(), eq(5), eq(2));
    }

    /**
     * A caller scoping a case to "everything that matches" has to be able to
     * tell a complete answer from a partial one, or the case silently gets the
     * first N matches and nobody knows.
     */
    @Test
    void flagsTheResultAsTruncatedWhenTheCapIsReached() throws IOException {
        when(indexClient.searchIds(any(), anyInt(), anyInt()))
                .thenReturn(List.of("m-1", "m-2", "m-3", "m-4", "m-5"));

        assertThat(service.resolveIds(criteria()).truncated()).isTrue();
    }

    @Test
    void wrapsAnIndexFailureRatherThanLeakingIt() throws IOException {
        when(indexClient.searchIds(any(), anyInt(), anyInt()))
                .thenThrow(new IOException("elasticsearch is down"));

        assertThatThrownBy(() -> service.resolveIds(criteria()))
                .isInstanceOf(SearchExecutionException.class);
    }

    @Test
    void returnsCustodiansOrderedAsTheAggregationReturnedThem() throws IOException {
        when(indexClient.aggregateSenders(50)).thenReturn(List.of(
                Map.entry("alice@stown.com", 12L),
                Map.entry("bob@stown.com", 4L)
        ));

        List<CustodianResponse> custodians = service.custodians();

        assertThat(custodians).containsExactly(
                new CustodianResponse("alice@stown.com", 12L),
                new CustodianResponse("bob@stown.com", 4L)
        );
    }

    @Test
    void returnsNoCustodiansForAnEmptyIndex() throws IOException {
        when(indexClient.aggregateSenders(anyInt())).thenReturn(List.of());

        assertThat(service.custodians()).isEmpty();
    }

    @Test
    void wrapsAnAggregationFailure() throws IOException {
        when(indexClient.aggregateSenders(anyInt()))
                .thenThrow(new IOException("aggregation unavailable"));

        assertThatThrownBy(() -> service.custodians())
                .isInstanceOf(SearchExecutionException.class);
    }
}
