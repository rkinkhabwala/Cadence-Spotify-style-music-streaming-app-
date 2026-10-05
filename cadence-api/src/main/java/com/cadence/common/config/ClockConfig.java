package com.cadence.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class ClockConfig {

    /** Single UTC clock so time-dependent logic is testable. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
