package com.cadence.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/**
 * S3-compatible object storage (MinIO locally). Only the plain S3 API is used so the server is swappable.
 *
 * @param endpoint          endpoint used by the API itself
 * @param publicEndpoint    endpoint embedded in presigned URLs (what browsers and players can reach)
 * @param autoCreateBuckets create missing buckets at startup (tests/dev; Compose creates them via minio-init)
 */
@ConfigurationProperties("cadence.s3")
public record S3Properties(
        URI endpoint,
        URI publicEndpoint,
        String region,
        String accessKey,
        String secretKey,
        String rawBucket,
        String hlsBucket,
        boolean autoCreateBuckets) {
}
