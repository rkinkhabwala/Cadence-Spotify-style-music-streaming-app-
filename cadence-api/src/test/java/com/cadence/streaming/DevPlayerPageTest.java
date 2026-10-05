package com.cadence.streaming;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DevPlayerPageTest {

    @Test
    void devPlayerPageUsesHlsJsAndThePlaybackApi() throws Exception {
        String html = new ClassPathResource("dev/player.html").getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("cdn.jsdelivr.net/npm/hls.js@", "'/playback/'", "hls.loadSource(start.manifestUrl)",
                "currentTime");
    }
}
