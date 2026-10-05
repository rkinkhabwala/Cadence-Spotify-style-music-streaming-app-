package com.cadence.streaming.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HlsPlaylistsTest {

    /** Exactly what FFmpeg 9 writes (see TranscoderIT). */
    static final String MASTER = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=104145,AVERAGE-BANDWIDTH=104654,CODECS="mp4a.40.2"
            96k/index.m3u8

            #EXT-X-STREAM-INF:BANDWIDTH=172975,AVERAGE-BANDWIDTH=173474,CODECS="mp4a.40.2"
            160k/index.m3u8

            #EXT-X-STREAM-INF:BANDWIDTH=227377,AVERAGE-BANDWIDTH=228050,CODECS="mp4a.40.2"
            320k/index.m3u8
            """;

    static final String MEDIA = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:10.007800,
            segment_000.ts
            #EXTINF:0.023222,
            segment_001.ts
            #EXT-X-ENDLIST
            """;

    @Test
    void freeUsersOnlySeeVariantsUpTo160() {
        String filtered = HlsPlaylists.filterMaster(MASTER, 160, uri -> uri + "?token=t");

        assertThat(filtered).contains("96k/index.m3u8?token=t", "160k/index.m3u8?token=t")
                .doesNotContain("320k").doesNotContain("BANDWIDTH=227377");
        assertThat(filtered).startsWith("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=104145");
        assertThat(filtered.lines().filter(l -> l.startsWith("#EXT-X-STREAM-INF")).count()).isEqualTo(2);
    }

    @Test
    void premiumUsersSeeAllVariants() {
        assertThat(HlsPlaylists.filterMaster(MASTER, 320, uri -> uri).lines()
                .filter(l -> l.endsWith("index.m3u8")).toList())
                .containsExactly("96k/index.m3u8", "160k/index.m3u8", "320k/index.m3u8");
    }

    @Test
    void failsIfNothingIsAllowed() {
        assertThatThrownBy(() -> HlsPlaylists.filterMaster(MASTER, 64, uri -> uri)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mediaPlaylistSegmentsAndUriAttributesAreRewritten() {
        String rewritten = HlsPlaylists.rewriteMedia(MEDIA + "#EXT-X-MAP:URI=\"init.mp4\"\n", s -> "https://cdn/" + s + "?sig");

        assertThat(rewritten).contains("https://cdn/segment_000.ts?sig", "https://cdn/segment_001.ts?sig",
                "#EXT-X-MAP:URI=\"https://cdn/init.mp4?sig\"", "#EXTINF:10.007800,", "#EXT-X-ENDLIST");
        assertThat(rewritten).doesNotContain("\nsegment_000.ts");
    }
}
