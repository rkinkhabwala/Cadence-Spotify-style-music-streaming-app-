package com.cadence.streaming;

import com.cadence.IntegrationTest;
import com.cadence.common.storage.ObjectStorage;
import com.cadence.support.StreamingFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StreamRangeIT extends IntegrationTest {

    @Autowired
    ObjectStorage storage;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcClient jdbc;

    private StreamingFixtures streaming;
    private UUID track;
    private byte[] file;
    private String token;

    @BeforeEach
    void setUp() {
        streaming = new StreamingFixtures(api, api.admin().accessToken(), storage, kafka, objectMapper, jdbc);
        track = streaming.readyTrack("Ranged " + UUID.randomUUID());
        file = StreamingFixtures.fallbackBytes(track);
        token = api.register().accessToken();
    }

    @Test
    void firstKilobyte() {
        ResponseEntity<byte[]> response = get("bytes=0-1023");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 0-1023/5000");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(1024);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(response.getHeaders().getContentType()).hasToString("audio/mp4");
        assertThat(response.getBody()).isEqualTo(Arrays.copyOfRange(file, 0, 1024));
    }

    @Test
    void openEndedAndSuffixRanges() {
        ResponseEntity<byte[]> openEnded = get("bytes=4000-");
        ResponseEntity<byte[]> suffix = get("bytes=-500");

        assertThat(openEnded.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(openEnded.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 4000-4999/5000");
        assertThat(openEnded.getBody()).isEqualTo(Arrays.copyOfRange(file, 4000, 5000));
        assertThat(suffix.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 4500-4999/5000");
        assertThat(suffix.getBody()).isEqualTo(Arrays.copyOfRange(file, 4500, 5000));
    }

    @Test
    void noRangeReturnsTheWholeFile() {
        ResponseEntity<byte[]> response = get(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(response.getBody()).isEqualTo(file);
    }

    @Test
    void invalidRangesAre416() {
        for (String range : new String[]{"bytes=6000-7000", "bytes=abc", "bytes=0-1,5-9"}) {
            ResponseEntity<byte[]> response = get(range);
            assertThat(response.getStatusCode()).as(range).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */5000");
            assertThat(new String(response.getBody())).contains("range-not-satisfiable");
        }
    }

    @Test
    void requiresAuthenticationAndAReadyTrack() {
        UUID processing = streaming.processingTrack("Unready " + UUID.randomUUID());

        assertThat(exchange(track, "bytes=0-1", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(processing, "bytes=0-1", token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exchange(UUID.randomUUID(), "bytes=0-1", token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<byte[]> get(String range) {
        return exchange(track, range, token);
    }

    private ResponseEntity<byte[]> exchange(UUID trackId, String range, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (range != null) {
            headers.set(HttpHeaders.RANGE, range);
        }
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return http.exchange("/api/v1/tracks/" + trackId + "/stream", HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }
}
