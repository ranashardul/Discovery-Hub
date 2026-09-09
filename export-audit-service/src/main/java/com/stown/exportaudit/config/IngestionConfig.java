package com.stown.exportaudit.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient pre-configured with the ingestion service read API base URL.
 * Used by {@link com.stown.exportaudit.service.IngestionClient} to fetch
 * message content by id so the export service never reads the ingestion
 * service's MongoDB collection directly (NFR-1).
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionConfig {

    @Bean
    public RestClient ingestionRestClient(IngestionProperties properties) {
        log.info("Ingestion service read API base URL: {}", properties.getBaseUrl());

        ClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        ((SimpleClientHttpRequestFactory) requestFactory)
                .setConnectTimeout(properties.getConnectTimeoutMs());
        ((SimpleClientHttpRequestFactory) requestFactory)
                .setReadTimeout(properties.getReadTimeoutMs());

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
