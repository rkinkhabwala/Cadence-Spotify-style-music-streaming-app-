package com.cadence.transcoder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties("cadence.s3")
public record S3Properties(URI endpoint, String region, String accessKey, String secretKey, String hlsBucket) {
}
