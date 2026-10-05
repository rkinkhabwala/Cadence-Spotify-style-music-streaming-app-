package com.cadence.streaming.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param manifestUrlTtl lifetime of the manifest URL returned by POST /playback (spec: 5 min)
 * @param segmentUrlTtl  base lifetime of presigned segment URLs and variant-playlist URLs (spec: 5 min); the
 *                       track duration is added because a VOD playlist is loaded once and played to the end
 */
@ConfigurationProperties("cadence.streaming")
public record StreamingProperties(Duration manifestUrlTtl, Duration segmentUrlTtl) {
}
