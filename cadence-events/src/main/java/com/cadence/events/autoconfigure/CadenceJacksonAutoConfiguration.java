package com.cadence.events.autoconfigure;

import com.cadence.events.CadenceJackson;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** Applies {@link CadenceJackson} settings to the Spring-managed ObjectMapper in every Cadence app. */
@AutoConfiguration(before = JacksonAutoConfiguration.class)
@ConditionalOnClass(Jackson2ObjectMapperBuilder.class)
public class CadenceJacksonAutoConfiguration {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer cadenceJacksonCustomizer() {
        return builder -> builder.postConfigurer(CadenceJackson::configure);
    }
}
