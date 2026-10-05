package com.cadence.events;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Jackson settings shared by every Cadence app and by event (de)serialization. */
public final class CadenceJackson {

    private CadenceJackson() {
    }

    public static ObjectMapper newObjectMapper() {
        return configure(new ObjectMapper());
    }

    public static ObjectMapper configure(ObjectMapper mapper) {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);  // Instants as ISO-8601 UTC strings
        mapper.disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES); // tolerate newer producers
        return mapper;
    }
}
