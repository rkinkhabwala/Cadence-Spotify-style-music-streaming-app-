package com.cadence.catalog.domain;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Accepted upload formats (spec 6) with their MIME type and a magic-byte check of the file header. */
public enum AudioFormat {
    MP3("mp3", "audio/mpeg"),
    FLAC("flac", "audio/flac"),
    WAV("wav", "audio/wav"),
    M4A("m4a", "audio/mp4");

    /** Bytes needed by {@link #matches(byte[])}. */
    public static final int HEADER_BYTES = 12;

    private final String extension;
    private final String mimeType;

    AudioFormat(String extension, String mimeType) {
        this.extension = extension;
        this.mimeType = mimeType;
    }

    public String extension() {
        return extension;
    }

    public String mimeType() {
        return mimeType;
    }

    public static Optional<AudioFormat> fromExtension(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String ext = value.strip().toLowerCase(Locale.ROOT);
        String normalized = ext.startsWith(".") ? ext.substring(1) : ext;
        return Arrays.stream(values()).filter(f -> f.extension.equals(normalized)).findFirst();
    }

    /** Whether the first bytes of a file look like this format. */
    public boolean matches(byte[] header) {
        return switch (this) {
            case MP3 -> startsWith(header, "ID3", 0)
                    || header.length >= 2 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xE0) == 0xE0; // MPEG frame sync
            case FLAC -> startsWith(header, "fLaC", 0);
            case WAV -> startsWith(header, "RIFF", 0) && startsWith(header, "WAVE", 8);
            case M4A -> startsWith(header, "ftyp", 4);
        };
    }

    private static boolean startsWith(byte[] data, String ascii, int offset) {
        byte[] expected = ascii.getBytes(StandardCharsets.US_ASCII);
        if (data.length < offset + expected.length) {
            return false;
        }
        return Arrays.equals(data, offset, offset + expected.length, expected, 0, expected.length);
    }
}
