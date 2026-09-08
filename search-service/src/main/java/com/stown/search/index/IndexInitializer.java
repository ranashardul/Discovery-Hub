package com.stown.search.index;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the Elasticsearch index at startup. A failure here is logged rather
 * than fatal: the index is re-created lazily on the first indexing attempt.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexInitializer implements ApplicationRunner {

    private final MessageIndexClient indexClient;

    @Override
    public void run(ApplicationArguments args) {
        try {
            indexClient.ensureIndex();
        } catch (Exception exception) {
            log.error(
                    "Could not initialise Elasticsearch index index={} reason={}",
                    indexClient.indexName(),
                    exception.getMessage()
            );
        }
    }
}
