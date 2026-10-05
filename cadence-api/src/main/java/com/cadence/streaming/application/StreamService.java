package com.cadence.streaming.application;

import com.cadence.common.error.CadenceException;
import com.cadence.common.error.NotFoundException;
import com.cadence.common.storage.ObjectStorage;
import com.cadence.events.HlsLayout;
import com.cadence.streaming.domain.ByteRange;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

/** HTTP Range fallback (spec 5): serves the single-file 160 kbps rendition of a READY track. */
@Service
public class StreamService {

    public record Slice(UUID trackId, ByteRange range) {
    }

    /** 416 with {@code Content-Range: bytes *}{@code /size}. */
    static class RangeNotSatisfiableException extends CadenceException {
        private final long size;

        RangeNotSatisfiableException(String detail, long size) {
            super(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "range-not-satisfiable", detail);
            this.size = size;
        }

        @Override
        public Map<String, String> headers() {
            return Map.of(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
        }
    }

    private final PlaybackService playback;
    private final ObjectStorage storage;

    StreamService(PlaybackService playback, ObjectStorage storage) {
        this.playback = playback;
        this.storage = storage;
    }

    /** Validates everything before any byte is written. */
    public Slice open(UUID trackId, String rangeHeader) {
        playback.playableTrack(trackId);
        long size = storage.head(storage.hlsBucket(), HlsLayout.fallbackKey(trackId))
                .orElseThrow(() -> new NotFoundException("Stream", trackId)).sizeBytes();
        try {
            return new Slice(trackId, ByteRange.parse(rangeHeader, size));
        } catch (ByteRange.InvalidRangeException e) {
            throw new RangeNotSatisfiableException(e.getMessage(), size);
        }
    }

    public InputStream read(Slice slice) {
        return storage.readRange(storage.hlsBucket(), HlsLayout.fallbackKey(slice.trackId()),
                slice.range().first(), slice.range().last());
    }
}
