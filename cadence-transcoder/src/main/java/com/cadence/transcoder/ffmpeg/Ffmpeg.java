package com.cadence.transcoder.ffmpeg;

import com.cadence.events.HlsLayout;
import com.cadence.transcoder.application.TranscodeException;
import com.cadence.transcoder.config.TranscoderProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** FFmpeg/ffprobe invocations used by a transcode job. */
@Component
public class Ffmpeg {

    private static final Pattern INTEGRATED_LOUDNESS = Pattern.compile("I:\\s+(-?\\d+(?:\\.\\d+)?) LUFS");

    private final ProcessRunner runner;
    private final TranscoderProperties properties;

    public Ffmpeg(ProcessRunner runner, TranscoderProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    /** Duration in ms; fails if the file has no audio stream or cannot be decoded. */
    public int probeDurationMs(Path source, Path workDir) {
        String streams = runner.run(List.of(properties.ffprobePath(), "-v", "error", "-select_streams", "a",
                "-show_entries", "stream=codec_type", "-of", "csv=p=0", source.toString()), workDir, properties.timeout()).stdout();
        if (!streams.contains("audio")) {
            throw new TranscodeException("The uploaded file contains no audio stream");
        }
        String duration = runner.run(List.of(properties.ffprobePath(), "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", source.toString()), workDir, properties.timeout()).stdout();
        return parseDurationMs(duration);
    }

    /**
     * One decode, four outputs: AAC HLS renditions at {@link HlsLayout#BITRATES_KBPS} with
     * {@link HlsLayout#SEGMENT_SECONDS}-second MPEG-TS segments and a master playlist, plus a single-file
     * 160 kbps AAC (faststart MP4) for Range playback.
     */
    public void transcode(Path source, Path outDir, Path workDir) {
        try {
            java.nio.file.Files.createDirectories(outDir); // FFmpeg creates the %v dirs but not their parent
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        runner.run(hlsCommand(properties.ffmpegPath(), source, outDir), workDir, properties.timeout());
    }

    static List<String> hlsCommand(String ffmpeg, Path source, Path outDir) {
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-nostdin", "-y", "-i", source.toString(), "-vn"));
        List<Integer> bitrates = HlsLayout.BITRATES_KBPS;
        for (int i = 0; i < bitrates.size(); i++) {
            cmd.addAll(List.of("-map", "0:a:0"));
        }
        cmd.addAll(List.of("-c:a", "aac", "-ar", "44100", "-ac", "2"));
        StringBuilder streamMap = new StringBuilder();
        for (int i = 0; i < bitrates.size(); i++) {
            cmd.addAll(List.of("-b:a:" + i, bitrates.get(i) + "k"));
            streamMap.append(i == 0 ? "" : " ").append("a:").append(i).append(",name:").append(HlsLayout.variantName(bitrates.get(i)));
        }
        cmd.addAll(List.of("-f", "hls", "-hls_time", Integer.toString(HlsLayout.SEGMENT_SECONDS),
                "-hls_playlist_type", "vod", "-hls_segment_type", "mpegts",
                "-hls_segment_filename", outDir.resolve("%v").resolve("segment_%03d.ts").toString(),
                "-master_pl_name", HlsLayout.MASTER,
                "-var_stream_map", streamMap.toString(),
                outDir.resolve("%v").resolve(HlsLayout.VARIANT_PLAYLIST).toString()));
        // second output: single-file fallback rendition
        cmd.addAll(List.of("-map", "0:a:0", "-vn", "-c:a", "aac", "-ar", "44100", "-ac", "2",
                "-b:a", HlsLayout.FALLBACK_KBPS + "k", "-movflags", "+faststart",
                outDir.resolve(HlsLayout.FALLBACK_FILE).toString()));
        return cmd;
    }

    /** EBU R128 integrated loudness in LUFS, or empty if it could not be measured. */
    public OptionalDouble loudnessLufs(Path source, Path workDir) {
        try {
            String stderr = runner.run(List.of(properties.ffmpegPath(), "-hide_banner", "-nostdin", "-i", source.toString(),
                    "-vn", "-af", "ebur128=framelog=quiet", "-f", "null", "-"), workDir, properties.timeout()).stderr();
            return parseIntegratedLoudness(stderr);
        } catch (TranscodeException e) {
            return OptionalDouble.empty();
        }
    }

    static int parseDurationMs(String ffprobeOutput) {
        try {
            double seconds = Double.parseDouble(ffprobeOutput.strip());
            if (seconds <= 0 || Double.isNaN(seconds)) {
                throw new TranscodeException("The uploaded audio has no duration");
            }
            return (int) Math.round(seconds * 1000);
        } catch (NumberFormatException e) {
            throw new TranscodeException("Cannot read the audio duration: " + ffprobeOutput.strip());
        }
    }

    /** The last {@code I: … LUFS} line is the summary's integrated loudness. */
    static OptionalDouble parseIntegratedLoudness(String ebur128Stderr) {
        Matcher matcher = INTEGRATED_LOUDNESS.matcher(ebur128Stderr);
        Double last = null;
        while (matcher.find()) {
            last = Double.parseDouble(matcher.group(1));
        }
        return last == null ? OptionalDouble.empty() : OptionalDouble.of(last);
    }
}
