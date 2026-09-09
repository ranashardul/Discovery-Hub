package com.stown.exportaudit.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Slf4j
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class S3Config {

    /**
     * Credentials come from the standard AWS chain, so the same code works with
     * AWS S3 (instance/profile/env credentials) and with MinIO
     * (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY). No credentials are hard-coded.
     */
    @Bean
    public S3Client s3Client(StorageProperties properties) {
        var builder = S3Client.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isPathStyleAccess())
                        .build());

        if (properties.hasCustomEndpoint()) {
            log.info("Using custom S3 endpoint {}", properties.getEndpoint());
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }

        return builder.build();
    }

    /**
     * Presigner used to mint time-limited download URLs for completed export
     * packages. It shares the same endpoint and region as the S3 client so
     * local MinIO URLs resolve correctly.
     */
    @Bean
    public S3Presigner s3Presigner(StorageProperties properties) {
        var builder = S3Presigner.builder()
                .region(Region.of(properties.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isPathStyleAccess())
                        .build());

        if (properties.hasCustomEndpoint()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }

        return builder.build();
    }
}
