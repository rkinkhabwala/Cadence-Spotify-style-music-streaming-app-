package com.cadence.common.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("cadence.outbox")
public record OutboxProperties(Duration pollInterval, int batchSize, Duration sendTimeout, Duration retention) {
}
