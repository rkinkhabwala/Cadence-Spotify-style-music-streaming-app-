package com.cadence.search.infrastructure.messaging;

import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * The indexer must not skip events: a skipped change would leave the index stale until the next reindex. Unlike the
 * API-wide policy (3 retries, then skip), failures here are retried indefinitely with exponential back-off capped at
 * 30 s, so an Elasticsearch outage just delays indexing. Malformed events are still skipped.
 */
@Configuration(proxyBeanMethods = false)
class SearchKafkaConfig {

    static final String FACTORY = "searchKafkaListenerContainerFactory";

    @Bean(FACTORY)
    ConcurrentKafkaListenerContainerFactory<Object, Object> searchKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2);
        backOff.setMaxInterval(30_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        factory.setCommonErrorHandler(handler);
        factory.setConcurrency(1); // one indexer thread: no races between denormalized updates
        return factory;
    }
}
