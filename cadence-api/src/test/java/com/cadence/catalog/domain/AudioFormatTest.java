package com.cadence.catalog.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AudioFormatTest {

    @Test
    void parsesExtensionsCaseInsensitively() {
        assertThat(AudioFormat.fromExtension(".MP3")).contains(AudioFormat.MP3);
        assertThat(AudioFormat.fromExtension("flac")).contains(AudioFormat.FLAC);
        assertThat(AudioFormat.fromExtension("exe")).isEmpty();
        assertThat(AudioFormat.fromExtension(null)).isEmpty();
        assertThat(AudioFormat.M4A.mimeType()).isEqualTo("audio/mp4");
    }

    @Test
    void recognisesMagicBytes() {
        assertThat(AudioFormat.MP3.matches(ascii("ID3\u0004\0\0\0\0\0\0\0\0"))).isTrue();
        assertThat(AudioFormat.MP3.matches(new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0})).isTrue();
        assertThat(AudioFormat.FLAC.matches(ascii("fLaC\0\0\0\"\0\0\0\0"))).isTrue();
        assertThat(AudioFormat.WAV.matches(ascii("RIFF$\0\0\0WAVE"))).isTrue();
        assertThat(AudioFormat.M4A.matches(ascii("\0\0\0 ftypM4A "))).isTrue();
    }

    @Test
    void rejectsOtherContent() {
        byte[] html = ascii("<!DOCTYPE html>");
        assertThat(AudioFormat.values()).noneMatch(f -> f.matches(html));
        assertThat(AudioFormat.WAV.matches(ascii("RIFF\0\0\0\0AVI "))).isFalse();
        assertThat(AudioFormat.FLAC.matches(new byte[0])).isFalse();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
