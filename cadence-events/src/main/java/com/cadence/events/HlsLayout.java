package com.cadence.events;

import java.util.List;
import java.util.UUID;

/**
 * Object layout of a transcoded track in the HLS bucket, shared by the transcoder (writer) and the API (reader):
 * <pre>
 * hls/{trackId}/master.m3u8
 * hls/{trackId}/{kbps}k/index.m3u8
 * hls/{trackId}/{kbps}k/segment_000.ts ...
 * hls/{trackId}/fallback_160k.m4a      single-file rendition for HTTP Range playback
 * hls/{trackId}/result.json            transcode result marker (idempotency)
 * </pre>
 */
public final class HlsLayout {

    public static final List<Integer> BITRATES_KBPS = List.of(96, 160, 320);
    public static final int FALLBACK_KBPS = 160;
    public static final int SEGMENT_SECONDS = 10;
    public static final String MASTER = "master.m3u8";
    public static final String VARIANT_PLAYLIST = "index.m3u8";
    public static final String FALLBACK_FILE = "fallback_" + FALLBACK_KBPS + "k.m4a";
    public static final String RESULT_FILE = "result.json";

    private HlsLayout() {
    }

    public static String prefix(UUID trackId) {
        return "hls/" + trackId + "/";
    }

    public static String masterKey(UUID trackId) {
        return prefix(trackId) + MASTER;
    }

    /** Directory name of a rendition, e.g. {@code 160k}. */
    public static String variantName(int kbps) {
        return kbps + "k";
    }

    public static String variantKey(UUID trackId, String variant, String file) {
        return prefix(trackId) + variant + "/" + file;
    }

    public static String fallbackKey(UUID trackId) {
        return prefix(trackId) + FALLBACK_FILE;
    }

    public static String resultKey(UUID trackId) {
        return prefix(trackId) + RESULT_FILE;
    }

    /** Parses {@code 160k} → 160, or -1 if the name is not a rendition directory. */
    public static int kbpsOf(String variant) {
        if (variant == null || !variant.matches("\\d{2,4}k")) {
            return -1;
        }
        return Integer.parseInt(variant.substring(0, variant.length() - 1));
    }
}
