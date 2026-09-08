package com.stown.ingestion.service;

import com.stown.ingestion.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Owns all object-store access. Attachment binaries live in S3 (or MinIO
 * locally) while MongoDB keeps only the metadata and the object location.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3StorageService {

    private final S3Client s3Client;
    private final StorageProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureBucketExists() {
        if (!properties.isCreateBucketOnStartup()) {
            return;
        }

        String bucket = properties.getBucket();

        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("Attachment bucket {} is available", bucket);
        } catch (NoSuchBucketException exception) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("Created attachment bucket {}", bucket);
        } catch (S3Exception exception) {
            log.warn(
                    "Could not verify attachment bucket {}: {}",
                    bucket,
                    exception.getMessage()
            );
        }
    }

    public String getBucket() {
        return properties.getBucket();
    }

    /**
     * Key used by the API before a message ID exists. The worker copies the
     * object to its durable location and removes the staged copy.
     */
    public String stagingKey(String requestId, int index, String filename) {
        return "%s/%s/%d-%s".formatted(
                properties.getStagingPrefix(),
                requestId,
                index,
                sanitize(filename)
        );
    }

    public String attachmentKey(String messageId, String attachmentId, String filename) {
        return "%s/%s/attachments/%s/%s".formatted(
                properties.getMessagePrefix(),
                messageId,
                attachmentId,
                sanitize(filename)
        );
    }

    public void upload(String key, byte[] content, String contentType) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.getBucket())
                        .key(key)
                        .contentType(contentType)
                        .contentLength((long) content.length)
                        .build(),
                RequestBody.fromBytes(content)
        );

        log.debug("Uploaded object key={} bytes={}", key, content.length);
    }

    public void copy(String sourceKey, String targetKey) {
        s3Client.copyObject(CopyObjectRequest.builder()
                .sourceBucket(properties.getBucket())
                .sourceKey(sourceKey)
                .destinationBucket(properties.getBucket())
                .destinationKey(targetKey)
                .build());

        log.debug("Copied object from {} to {}", sourceKey, targetKey);
    }

    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .build());

        log.debug("Deleted object key={}", key);
    }

    public HeadObjectResponse head(String key) {
        return s3Client.headObject(HeadObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .build());
    }

    public boolean exists(String key) {
        try {
            head(key);
            return true;
        } catch (S3Exception exception) {
            return false;
        }
    }

    public String objectUrl(String key) {
        String encodedKey = encodeKey(key);

        if (!properties.getPublicBaseUrl().isBlank()) {
            return "%s/%s/%s".formatted(
                    trimTrailingSlash(properties.getPublicBaseUrl()),
                    properties.getBucket(),
                    encodedKey
            );
        }

        if (properties.hasCustomEndpoint()) {
            return "%s/%s/%s".formatted(
                    trimTrailingSlash(properties.getEndpoint()),
                    properties.getBucket(),
                    encodedKey
            );
        }

        return "https://%s.s3.%s.amazonaws.com/%s".formatted(
                properties.getBucket(),
                properties.getRegion(),
                encodedKey
        );
    }

    public String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }

    private String encodeKey(String key) {
        StringBuilder encoded = new StringBuilder();

        for (String segment : key.split("/", -1)) {
            if (!encoded.isEmpty()) {
                encoded.append('/');
            }
            encoded.append(URLEncoder.encode(segment, StandardCharsets.UTF_8)
                    .replace("+", "%20"));
        }

        return encoded.toString();
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String sanitize(String filename) {
        String cleaned = filename == null ? "" : filename.trim().replace('\\', '/');
        int lastSlash = cleaned.lastIndexOf('/');

        if (lastSlash >= 0) {
            cleaned = cleaned.substring(lastSlash + 1);
        }

        cleaned = cleaned.replaceAll("[^A-Za-z0-9._-]", "_");

        return cleaned.isBlank() ? "attachment" : cleaned;
    }
}
