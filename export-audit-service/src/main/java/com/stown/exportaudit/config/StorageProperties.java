package com.stown.exportaudit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /** Bucket holding attachment binaries owned by ingestion (read-only here). */
    private String sourceBucket = "discovery-hub-attachments";

    /** Bucket that holds generated export packages. */
    private String exportBucket = "discovery-hub-exports";

    /** AWS region. Also required by the SDK when talking to MinIO. */
    private String region = "ap-south-1";

    /** Custom S3 endpoint, for example a MinIO URL. Empty means real AWS S3. */
    private String endpoint = "";

    /** MinIO requires path-style access; AWS S3 works with either. */
    private boolean pathStyleAccess = true;

    /** Create the export bucket at startup when it does not exist. */
    private boolean createBucketOnStartup = true;

    /** Prefix for export package objects. */
    private String exportPrefix = "exports";

    /** Lifetime of a presigned download URL, in seconds. */
    private long downloadExpirySeconds = 900;

    public boolean hasCustomEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }
}
