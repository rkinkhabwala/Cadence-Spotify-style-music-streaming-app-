package com.cadence.transcoder.storage;

import com.cadence.transcoder.config.S3Properties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** S3 operations of the transcoder (plain S3 API, so any S3-compatible store works). */
@Component
public class HlsStorage {

    private final S3Client s3;
    private final S3Properties properties;

    public HlsStorage(S3Client s3, S3Properties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    public String hlsBucket() {
        return properties.hlsBucket();
    }

    /** @return {@code false} if the object does not exist */
    public boolean download(String bucket, String key, Path target) {
        try {
            s3.getObject(g -> g.bucket(bucket).key(key), target);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    public Optional<String> readText(String key) {
        try {
            return Optional.of(s3.getObjectAsBytes(g -> g.bucket(properties.hlsBucket()).key(key)).asUtf8String());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    public void putText(String key, String content, String contentType) {
        s3.putObject(p -> p.bucket(properties.hlsBucket()).key(key).contentType(contentType),
                RequestBody.fromString(content, StandardCharsets.UTF_8));
    }

    /** Uploads every file under {@code dir} to {@code prefix + relative path}. */
    public void uploadDirectory(Path dir, String prefix) {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String key = prefix + dir.relativize(file).toString().replace('\\', '/');
                s3.putObject(p -> p.bucket(properties.hlsBucket()).key(key).contentType(contentType(key)),
                        RequestBody.fromFile(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void deletePrefix(String prefix) {
        String token = null;
        do {
            String continuation = token;
            ListObjectsV2Response page = s3.listObjectsV2(l -> l.bucket(properties.hlsBucket()).prefix(prefix)
                    .continuationToken(continuation));
            List<ObjectIdentifier> ids = page.contents().stream()
                    .map(o -> ObjectIdentifier.builder().key(o.key()).build()).toList();
            if (!ids.isEmpty()) {
                s3.deleteObjects(d -> d.bucket(properties.hlsBucket()).delete(Delete.builder().objects(ids).quiet(true).build()));
            }
            token = page.isTruncated() ? page.nextContinuationToken() : null;
        } while (token != null);
    }

    static String contentType(String key) {
        if (key.endsWith(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        }
        if (key.endsWith(".ts")) {
            return "video/mp2t";
        }
        if (key.endsWith(".m4a")) {
            return "audio/mp4";
        }
        if (key.endsWith(".json")) {
            return "application/json";
        }
        return "application/octet-stream";
    }
}
