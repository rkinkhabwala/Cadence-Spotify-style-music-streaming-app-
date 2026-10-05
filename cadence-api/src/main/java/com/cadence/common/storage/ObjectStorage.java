package com.cadence.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Thin wrapper over the S3 API used by every context that stores or serves audio. */
@Component
public class ObjectStorage implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorage.class);

    public record PresignedRequest(URI url, String method, Map<String, String> headers, Instant expiresAt) {
    }

    public record ObjectInfo(long sizeBytes, String contentType) {
    }

    private final S3Client s3;
    private final S3Presigner presigner;
    private final S3Properties properties;
    private final Clock clock;

    public ObjectStorage(S3Client s3, S3Presigner presigner, S3Properties properties, Clock clock) {
        this.s3 = s3;
        this.presigner = presigner;
        this.properties = properties;
        this.clock = clock;
    }

    public String rawBucket() {
        return properties.rawBucket();
    }

    public String hlsBucket() {
        return properties.hlsBucket();
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.autoCreateBuckets()) {
            return;
        }
        for (String bucket : List.of(properties.rawBucket(), properties.hlsBucket())) {
            try {
                s3.headBucket(b -> b.bucket(bucket));
            } catch (NoSuchBucketException e) {
                s3.createBucket(b -> b.bucket(bucket));
                log.info("Created bucket {}", bucket);
            }
        }
    }

    /**
     * Presigned PUT. Content type and length are part of the signature, so the client must upload exactly
     * {@code contentLength} bytes with that {@code Content-Type}; this is what enforces the upload size limit.
     */
    public PresignedRequest presignPut(String bucket, String key, String contentType, long contentLength, Duration ttl) {
        var presigned = presigner.presignPutObject(p -> p.signatureDuration(ttl)
                .putObjectRequest(o -> o.bucket(bucket).key(key).contentType(contentType).contentLength(contentLength)));
        return new PresignedRequest(URI.create(presigned.url().toString()), "PUT",
                Map.of("Content-Type", contentType), clock.instant().plus(ttl));
    }

    public URI presignGet(String bucket, String key, Duration ttl) {
        var presigned = presigner.presignGetObject(p -> p.signatureDuration(ttl).getObjectRequest(o -> o.bucket(bucket).key(key)));
        return URI.create(presigned.url().toString());
    }

    public Optional<ObjectInfo> head(String bucket, String key) {
        try {
            HeadObjectResponse head = s3.headObject(h -> h.bucket(bucket).key(key));
            return Optional.of(new ObjectInfo(head.contentLength(), head.contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** Bytes {@code [first, last]} inclusive. */
    public ResponseInputStream<GetObjectResponse> readRange(String bucket, String key, long first, long last) {
        return s3.getObject(g -> g.bucket(bucket).key(key).range("bytes=" + first + "-" + last));
    }

    public byte[] readAll(String bucket, String key) {
        return s3.getObjectAsBytes(g -> g.bucket(bucket).key(key)).asByteArray();
    }

    public InputStream read(String bucket, String key) {
        return s3.getObject(g -> g.bucket(bucket).key(key));
    }

    public void put(String bucket, String key, byte[] content, String contentType) {
        s3.putObject(p -> p.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(content));
    }

    public void delete(String bucket, String key) {
        s3.deleteObject(d -> d.bucket(bucket).key(key));
    }

    /** Deletes every object under {@code prefix}; returns how many were deleted. */
    public int deletePrefix(String bucket, String prefix) {
        int deleted = 0;
        String token = null;
        do {
            String continuation = token;
            ListObjectsV2Response page = s3.listObjectsV2(l -> l.bucket(bucket).prefix(prefix).continuationToken(continuation));
            List<ObjectIdentifier> ids = page.contents().stream()
                    .map(o -> ObjectIdentifier.builder().key(o.key()).build()).toList();
            if (!ids.isEmpty()) {
                s3.deleteObjects(d -> d.bucket(bucket).delete(Delete.builder().objects(ids).quiet(true).build()));
                deleted += ids.size();
            }
            token = page.isTruncated() ? page.nextContinuationToken() : null;
        } while (token != null);
        return deleted;
    }

    public List<String> list(String bucket, String prefix) {
        return s3.listObjectsV2Paginator(l -> l.bucket(bucket).prefix(prefix)).contents().stream()
                .map(o -> o.key()).toList();
    }
}
