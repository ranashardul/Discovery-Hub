package com.stown.exportaudit.api;

/**
 * Response for the download endpoint. Returns an expiring presigned S3 URL the
 * caller can use to fetch a completed export package.
 */
public record DownloadUrlResponse(
        String exportId,
        String downloadUrl,
        String s3Bucket,
        String s3Key
) {
}
