package com.cadence.transcoder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * @param timeout  maximum wall time of one FFmpeg/ffprobe invocation
 * @param workDir  parent of per-job temp directories (default: java.io.tmpdir)
 */
@ConfigurationProperties("cadence.transcoder")
public record TranscoderProperties(String ffmpegPath, String ffprobePath, Duration timeout, Path workDir) {

    public Path effectiveWorkDir() {
        return workDir != null ? workDir : Path.of(System.getProperty("java.io.tmpdir"));
    }
}
