package com.cadence.streaming.domain;

import com.cadence.events.HlsLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure M3U8 rewriting used to serve manifests through the API. */
public final class HlsPlaylists {

    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]+)\"");

    private HlsPlaylists() {
    }

    /**
     * Keeps only variants whose rendition directory (e.g. {@code 160k/index.m3u8}) is at most {@code maxKbps} and
     * rewrites their URIs.
     *
     * @throws IllegalStateException if no variant is left
     */
    public static String filterMaster(String master, int maxKbps, UnaryOperator<String> rewriteUri) {
        List<String> out = new ArrayList<>();
        String pendingStreamInf = null;
        int kept = 0;
        for (String raw : master.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                pendingStreamInf = line;
                continue;
            }
            if (pendingStreamInf != null && !line.startsWith("#")) {
                int kbps = HlsLayout.kbpsOf(line.split("/")[0]);
                if (kbps > 0 && kbps <= maxKbps) {
                    out.add(pendingStreamInf);
                    out.add(rewriteUri.apply(line));
                    kept++;
                }
                pendingStreamInf = null;
                continue;
            }
            out.add(line);
        }
        if (kept == 0) {
            throw new IllegalStateException("No variant at or below " + maxKbps + " kbps");
        }
        return String.join("\n", out) + "\n";
    }

    /** Rewrites every media URI (segment lines and {@code URI="..."} attributes) of a media playlist. */
    public static String rewriteMedia(String playlist, UnaryOperator<String> rewriteUri) {
        List<String> out = new ArrayList<>();
        for (String raw : playlist.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (!line.startsWith("#")) {
                out.add(rewriteUri.apply(line));
            } else {
                Matcher matcher = URI_ATTRIBUTE.matcher(line);
                out.add(matcher.find()
                        ? line.substring(0, matcher.start(1)) + rewriteUri.apply(matcher.group(1)) + line.substring(matcher.end(1))
                        : line);
            }
        }
        return String.join("\n", out) + "\n";
    }
}
