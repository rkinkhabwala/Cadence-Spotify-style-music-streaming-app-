package com.cadence.transcoder.application;

import com.cadence.events.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Transcodes one uploaded track. Slice 1.1 stub: logs the job; FFmpeg → HLS arrives in slice 1.4. */
@Component
public class TranscodeJobHandler {

    private static final Logger log = LoggerFactory.getLogger(TranscodeJobHandler.class);

    public void handle(EventEnvelope event) {
        log.info("Received transcode job {} for track {} (not implemented yet)", event.eventId(), event.itemId());
    }
}
