package com.stown.casehold.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Slf4j
@Configuration
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfig {

    /**
     * RestClient for the search service, used by the hold scope preview.
     *
     * <p>Timeouts are short and explicit. The preview runs while a reviewer
     * waits on a dialog, and it is advisory — a slow search service should
     * fail the count quickly rather than hold the request open.
     */
    @Bean
    public RestClient searchRestClient(SearchProperties properties) {
        log.info("Search service base URL: {}", properties.getBaseUrl());

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getReadTimeoutMs());

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
