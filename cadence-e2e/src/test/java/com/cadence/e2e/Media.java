package com.cadence.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** FFmpeg helpers: test audio generation and an HLS client that really decodes (and seeks) a manifest URL. */
final class Media {

    record Result(int exitCode, String output) {
    }

    private Media() {
    }

    static byte[] toneMp3(int seconds, int frequency) throws Exception {
        Path file = Files.createTempFile("e2e-tone", ".mp3");
        try {
            Result result = run("ffmpeg", "-hide_banner", "-nostdin", "-y", "-f", "lavfi",
                    "-i", "sine=frequency=" + frequency + ":duration=" + seconds, "-ac", "2", "-c:a", "libmp3lame",
                    "-b:a", "192k", file.toString());
            if (result.exitCode() != 0) {
                throw new IllegalStateException(result.output());
            }
            return Files.readAllBytes(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Decodes {@code seconds} of audio starting at {@code seekSeconds} through the HLS manifest URL. */
    static Result playFrom(String manifestUrl, int seekSeconds, int seconds) throws Exception {
        return run("ffmpeg", "-hide_banner", "-nostdin", "-v", "error", "-ss", Integer.toString(seekSeconds),
                "-i", manifestUrl, "-t", Integer.toString(seconds), "-f", "null", "-");
    }

    /** ffprobe's view of an HLS manifest: one line per program (variant) with its bit rate. */
    static Result probe(String manifestUrl) throws Exception {
        return run("ffprobe", "-v", "error", "-show_entries", "format=duration:program_tags=variant_bitrate",
                "-of", "compact", manifestUrl);
    }

    static Result run(String... command) throws Exception {
        Process process = new ProcessBuilder(new ArrayList<>(List.of(command))).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        return new Result(process.waitFor(), output);
    }
}
