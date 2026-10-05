package com.cadence.transcoder;

import com.cadence.events.EventEnvelope;
import com.cadence.events.EventTypes;
import com.cadence.events.ItemTypes;
import com.cadence.events.Topics;
import com.cadence.events.UuidV7;
import com.cadence.transcoder.application.TranscodeJobHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(TranscoderApplicationIT.Containers.class)
class TranscoderApplicationIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        KafkaContainer kafka() {
            return new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));
        }
    }

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    ObjectMapper objectMapper;
    @MockitoSpyBean
    TranscodeJobHandler handler;

    @Test
    void consumesTrackUploadedEvents() {
        EventEnvelope event = EventEnvelope.create(EventTypes.TRACK_UPLOADED, Instant.now(), null,
                ItemTypes.SONG, UuidV7.generate(), null, objectMapper);

        kafka.send(Topics.CATALOG_TRACK_UPLOADED, event.itemId().toString(), event.toJson(objectMapper));

        verify(handler, timeout(30_000)).handle(event);
    }
}
