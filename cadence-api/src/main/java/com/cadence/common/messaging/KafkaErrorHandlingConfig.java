package com.cadence.common.messaging;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consumer error policy for every @KafkaListener in the API: 3 retries 1 s apart, then the record is logged and
 * skipped. Malformed events ({@link IllegalArgumentException}) are skipped immediately.
 */
@Configuration(proxyBeanMethods = false)
class KafkaErrorHandlingConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(new FixedBackOff(1_000, 3));
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
