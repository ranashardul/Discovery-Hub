package com.stown.exportaudit.service;

import com.stown.exportaudit.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Owns all object-store access for the export service. Attachment binaries are
 * read from the source bucket (owned by ingestion) and generated export
 * packages are written to a dedicated export bucket.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3StorageService {

    private final S3Client s3Client;
    private final StorageProperties properties;
    private final S3Presigner s3Presigner;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureExportBucketExists() {
        if (!properties.isCreateBucketOnStartup()) {
            return;
        }

        String bucket = properties.getExportBucket();

        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("Export bucket {} is available", bucket);
        } catch (NoSuchBucketException exception) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("Created export bucket {}", bucket);
        } catch (S3Exception exception) {
            log.warn(
                    "Could not verify export bucket {}: {}",
                    bucket,
                    exception.getMessage()
            );
        }
    }

    public String getExportBucket() {
        return properties.getExportBucket();
    }

    /** S3 key for a completed export package, namespaced under the export id. */
    public String exportKey(String exportId, String filename) {
        return "%s/%s/%s".formatted(properties.getExportPrefix(), exportId, filename);
    }

    /**
     * Reads an attachment binary from the source bucket. The bucket and key
     * come from the attachment metadata written by the ingestion service.
     */
    public byte[] readAttachment(String bucket, String key) {
        try {
            return s3Client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .build()
            ).asByteArray();
        } catch (Exception exception) {
            throw new ExportStorageException(
                    "Failed to read attachment s3://%s/%s: %s".formatted(
                            bucket, key, exception.getMessage()
                    ),
                    exception
            );
        }
    }

    public void uploadPackage(String key, byte[] content) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.getExportBucket())
                        .key(key)
                        .contentType("application/zip")
                        .contentLength((long) content.length)
                        .build(),
                RequestBody.fromBytes(content)
        );

        log.debug("Uploaded export package key={} bytes={}", key, content.length);
    }

    public boolean packageExists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getExportBucket())
                    .key(key)
                    .build());
            return true;
        } catch (S3Exception exception) {
            return false;
        }
    }

    /**
     * Generates a time-limited presigned URL for downloading a completed
     * export package.
     */
    public String presignedDownloadUrl(String key) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(properties.getDownloadExpirySeconds()))
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(properties.getExportBucket())
                        .key(key)
                        .build())
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    public String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }
}
