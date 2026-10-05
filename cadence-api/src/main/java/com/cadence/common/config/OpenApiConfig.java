package com.cadence.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    OpenAPI cadenceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Cadence API")
                .version("v1")
                .description("Spotify-style music streaming API. Errors use RFC 7807 problem details; "
                        + "collections use cursor pagination (?limit=&cursor= → {items, nextCursor})."));
    }
}
