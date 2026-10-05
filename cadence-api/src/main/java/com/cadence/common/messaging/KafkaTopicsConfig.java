package com.cadence.common.messaging;

import com.cadence.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/** Declares every topic from spec 3.4; Spring's KafkaAdmin creates missing ones at startup. */
@Configuration(proxyBeanMethods = false)
class KafkaTopicsConfig {

    @Bean
    KafkaAdmin.NewTopics cadenceTopics(@Value("${cadence.kafka.partitions}") int partitions,
                                       @Value("${cadence.kafka.replication-factor}") short replicas) {
        return new KafkaAdmin.NewTopics(Topics.ALL.stream()
                .map(name -> TopicBuilder.name(name).partitions(partitions).replicas(replicas).build())
                .toArray(NewTopic[]::new));
    }
}
