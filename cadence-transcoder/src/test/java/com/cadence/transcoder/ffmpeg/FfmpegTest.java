package com.cadence.transcoder.ffmpeg;

import com.cadence.transcoder.application.TranscodeException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FfmpegTest {

    @Test
    void parsesDuration() {
        assertThat(Ffmpeg.parseDurationMs("10.000000\n")).isEqualTo(10_000);
        assertThat(Ffmpeg.parseDurationMs("183.4567")).isEqualTo(183_457);
        assertThatThrownBy(() -> Ffmpeg.parseDurationMs("N/A")).isInstanceOf(TranscodeException.class);
        assertThatThrownBy(() -> Ffmpeg.parseDurationMs("0")).isInstanceOf(TranscodeException.class);
    }

    @Test
    void parsesTheSummaryIntegratedLoudness() {
        String stderr = """
                [Parsed_ebur128_0 @ 0x1] t: 9.9  M: -22.1 S: -22.0  I: -23.5 LUFS  LRA: 0.0 LU
                [Parsed_ebur128_0 @ 0x1] Summary:

                  Integrated loudness:
                    I:         -22.0 LUFS
                    Threshold: -32.0 LUFS
                """;

        assertThat(Ffmpeg.parseIntegratedLoudness(stderr)).hasValue(-22.0);
        assertThat(Ffmpeg.parseIntegratedLoudness("no summary")).isEmpty();
    }

    @Test
    void hlsCommandProducesThreeRenditionsTenSecondSegmentsAndAFallback() {
        List<String> cmd = Ffmpeg.hlsCommand("ffmpeg", Path.of("/w/source.mp3"), Path.of("/w/out"));

        String joined = String.join(" ", cmd);
        assertThat(joined).contains("-b:a:0 96k", "-b:a:1 160k", "-b:a:2 320k", "-hls_time 10",
                "-hls_playlist_type vod", "-master_pl_name master.m3u8",
                "a:0,name:96k a:1,name:160k a:2,name:320k", "/w/out/%v/index.m3u8", "/w/out/fallback_160k.m4a");
        assertThat(cmd.stream().filter("-map"::equals).count()).isEqualTo(4);
    }

    @Test
    void runnerReportsExitCodesAndTimeouts() throws Exception {
        ProcessRunner runner = new ProcessRunner();
        Path dir = java.nio.file.Files.createTempDirectory("runner");

        assertThat(runner.run(List.of("sh", "-c", "echo hi; echo warn >&2"), dir, java.time.Duration.ofSeconds(5)).stdout())
                .isEqualTo("hi\n");
        assertThatThrownBy(() -> runner.run(List.of("sh", "-c", "echo broken >&2; exit 3"), dir, java.time.Duration.ofSeconds(5)))
                .isInstanceOf(TranscodeException.class).hasMessageContaining("exited with 3").hasMessageContaining("broken");
        long start = System.nanoTime();
        assertThatThrownBy(() -> runner.run(List.of("sleep", "30"), dir, java.time.Duration.ofMillis(300)))
                .isInstanceOf(TranscodeException.class).hasMessageContaining("timed out");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(10_000);
    }
}
