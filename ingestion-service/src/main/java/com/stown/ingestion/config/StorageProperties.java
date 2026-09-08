package com.stown.ingestion.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /** Target bucket for attachment binaries. */
    private String bucket;

    /** AWS region. Also required by the SDK when talking to MinIO. */
    private String region = "ap-south-1";

    /**
     * Custom S3 endpoint, for example a MinIO URL. Empty means real AWS S3.
     */
    private String endpoint = "";

    /** MinIO requires path-style access; AWS S3 works with either. */
    private boolean pathStyleAccess = true;

    /** Create the bucket at startup when it does not exist. */
    private boolean createBucketOnStartup = true;

    /** Prefix for binaries uploaded by the API before the worker claims them. */
    private String stagingPrefix = "staging";

    /** Prefix for the durable attachment objects owned by a message. */
    private String messagePrefix = "messages";

    /**
     * Optional public base URL used to build stored attachment URLs, for cases
     * where the browser-facing host differs from the internal endpoint.
     */
    private String publicBaseUrl = "";

    public boolean hasCustomEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }
}
