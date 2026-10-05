package com.cadence.catalog.application;

import com.cadence.catalog.domain.Track;
import com.cadence.catalog.infrastructure.TrackRepository;
import com.cadence.common.outbox.ProcessedEvents;
import com.cadence.events.EntityChangedPayload.Action;
import com.cadence.events.EventEnvelope;
import com.cadence.events.EventTypes;
import com.cadence.events.ItemTypes;
import com.cadence.events.TrackTranscodeFailedPayload;
import com.cadence.events.TrackTranscodedPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/** Applies transcoder results to tracks (spec 3.3 step 6). Idempotent per event and ignores results of stale jobs. */
@Service
public class TranscodeResultService {

    static final String CONSUMER = "catalog.transcode-results";
    private static final Logger log = LoggerFactory.getLogger(TranscodeResultService.class);

    private final TrackRepository tracks;
    private final ProcessedEvents processedEvents;
    private final CatalogEvents events;
    private final TrackSummaries summaries;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    TranscodeResultService(TrackRepository tracks, ProcessedEvents processedEvents, CatalogEvents events,
                           TrackSummaries summaries, ObjectMapper objectMapper, Clock clock) {
        this.tracks = tracks;
        this.processedEvents = processedEvents;
        this.events = events;
        this.summaries = summaries;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public void apply(EventEnvelope event) {
        if (!processedEvents.markProcessed(CONSUMER, event.eventId())) {
            log.debug("Duplicate transcode result {} ignored", event.eventId());
            return;
        }
        Optional<Track> found = tracks.findById(event.itemId());
        if (found.isEmpty()) {
            log.info("Transcode result for deleted track {} ignored", event.itemId());
            return;
        }
        Track track = found.get();
        boolean applied = switch (event.eventType()) {
            case EventTypes.TRACK_TRANSCODED -> {
                TrackTranscodedPayload result = event.payloadAs(TrackTranscodedPayload.class, objectMapper);
                yield track.markReady(result.jobId(), result.durationMs(), result.loudnessLufs(), clock.instant());
            }
            case EventTypes.TRACK_TRANSCODE_FAILED -> {
                TrackTranscodeFailedPayload result = event.payloadAs(TrackTranscodeFailedPayload.class, objectMapper);
                yield track.markFailed(result.jobId(), result.reason(), clock.instant());
            }
            default -> throw new IllegalArgumentException("Unexpected event type " + event.eventType());
        };
        if (!applied) {
            log.info("Stale transcode result {} for track {} ignored (current job {})", event.eventId(), track.getId(),
                    track.getTranscodeJobId());
            return;
        }
        tracks.flush();
        events.entityChanged(ItemTypes.SONG, track.getId(), Action.UPDATED, summaries.of(track));
    }
}
