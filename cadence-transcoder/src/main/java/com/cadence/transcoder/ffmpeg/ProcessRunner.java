package com.cadence.transcoder.ffmpeg;

import com.cadence.transcoder.application.TranscodeException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs an external command with a timeout. stdout and stderr go to files in the job directory (no pipe
 * buffers to deadlock on), and a non-zero exit or timeout becomes a {@link TranscodeException} carrying the
 * end of stderr.
 */
@Component
public class ProcessRunner {

    public record Result(String stdout, String stderr) {
    }

    private static final int STDERR_TAIL_CHARS = 1500;

    public Result run(List<String> command, Path workDir, Duration timeout) {
        Path out = workDir.resolve("cmd-" + System.nanoTime() + ".out");
        Path err = workDir.resolve("cmd-" + System.nanoTime() + ".err");
        Process process;
        try {
            process = new ProcessBuilder(command).directory(workDir.toFile())
                    .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        } catch (IOException e) {
            throw new TranscodeException("Cannot start " + command.getFirst() + ": " + e.getMessage(), e);
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                throw new TranscodeException(command.getFirst() + " timed out after " + timeout);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new TranscodeException("Interrupted while running " + command.getFirst(), e);
        }
        String stdout = read(out);
        String stderr = read(err);
        if (process.exitValue() != 0) {
            throw new TranscodeException(command.getFirst() + " exited with " + process.exitValue() + ": " + tail(stderr));
        }
        return new Result(stdout, stderr);
    }

    private static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    static String tail(String text) {
        String trimmed = text.strip();
        return trimmed.length() <= STDERR_TAIL_CHARS ? trimmed : "…" + trimmed.substring(trimmed.length() - STDERR_TAIL_CHARS);
    }
}
