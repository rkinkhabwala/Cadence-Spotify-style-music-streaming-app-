package com.cadence.streaming.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param manifestUrlTtl lifetime of the manifest URL returned by POST /playback (spec: 5 min)
 * @param segmentUrlTtl  base lifetime of presigned segment URLs and variant-playlist URLs (spec: 5 min); the
 *                       track duration is added because a VOD playlist is loaded once and played to the end
 * @param freePlan       free-plan rules (spec 4, D96)
 */
@ConfigurationProperties("cadence.streaming")
public record StreamingProperties(Duration manifestUrlTtl, Duration segmentUrlTtl, FreePlan freePlan) {

    /**
     * @param skipsPerWindow skips allowed in any rolling {@code skipWindow} (spec 4: 6 per hour)
     * @param adEvery        every n-th playback start of a free user carries an ad slot (0 = never)
     * @param adSlotDuration length of the ad slot placeholder
     */
    public record FreePlan(int skipsPerWindow, Duration skipWindow, int adEvery, Duration adSlotDuration) {
    }
}
