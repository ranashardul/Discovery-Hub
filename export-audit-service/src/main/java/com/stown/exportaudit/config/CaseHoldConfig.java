package com.stown.exportaudit.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Slf4j
@Configuration
@EnableConfigurationProperties(CaseHoldProperties.class)
public class CaseHoldConfig {

    /**
     * RestClient pre-configured with the Case & Hold service base URL. Used
     * by {@code CaseHoldClient} to resolve the evidence IDs that belong to a
     * case or a legal hold.
     */
    @Bean
    public RestClient caseHoldRestClient(CaseHoldProperties properties) {
        log.info("Case & Hold service base URL: {}", properties.getBaseUrl());

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .build();
    }
}
