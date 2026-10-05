package com.cadence.streaming.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serves the hls.js test page at /dev/player.html, only with the {@code dev} profile (spec 12 #3). */
@Configuration(proxyBeanMethods = false)
@Profile("dev")
class DevPlayerConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/dev/**").addResourceLocations("classpath:/dev/");
    }
}
