package com.cadence.transcoder.config;

import com.cadence.transcoder.messaging.TranscodeFailurePublisher;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({TranscoderProperties.class, S3Properties.class})
class TranscoderConfig {

    private static final Logger log = LoggerFactory.getLogger(TranscoderConfig.class);

    @Bean(destroyMethod = "close")
    S3Client s3Client(S3Properties properties) {
        return S3Client.builder()
                .endpointOverride(properties.endpoint())
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    /**
     * Infrastructure errors (S3/Kafka hiccups) are retried 3 times, 5 s apart. If a job still cannot be processed, a
     * {@code track-transcode-failed} event is published so the track never stays PROCESSING forever.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(TranscodeFailurePublisher failures) {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (ConsumerRecord<?, ?> record, Exception e) -> {
                    log.error("Giving up on record {}-{}@{}", record.topic(), record.partition(), record.offset(), e);
                    failures.publishForRecord(String.valueOf(record.value()), "Transcoder error: " + rootMessage(e));
                },
                new FixedBackOff(5_000, 3));
        handler.addNotRetryableExceptions(IllegalArgumentException.class); // malformed event
        return handler;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
