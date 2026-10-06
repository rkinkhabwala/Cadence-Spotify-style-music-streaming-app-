package com.cadence.common.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code cadence.outbox.pending}: events written but not yet published to Kafka (D98). Uses the partial index on
 * unpublished rows, so the per-scrape query is cheap; a growing value means Kafka or the poller is stuck.
 */
@Component
class OutboxMetrics implements MeterBinder {

    private final JdbcClient jdbc;

    OutboxMetrics(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("cadence.outbox.pending", this::pending)
                .description("Outbox events not yet published to Kafka")
                .register(registry);
    }

    private double pending() {
        try {
            return jdbc.sql("SELECT count(*) FROM outbox_event WHERE published_at IS NULL").query(Long.class).single();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
