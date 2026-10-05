-- Owner: common (shared kernel). Transactional outbox + idempotent-consumer bookkeeping.

CREATE TABLE outbox_event (
    id           uuid PRIMARY KEY,               -- = envelope eventId
    seq          bigint GENERATED ALWAYS AS IDENTITY,
    topic        varchar(255) NOT NULL,
    event_key    varchar(255) NOT NULL,
    event_type   varchar(100) NOT NULL,
    payload      jsonb        NOT NULL,          -- full EventEnvelope JSON
    created_at   timestamptz  NOT NULL DEFAULT now(),
    published_at timestamptz,
    attempts     integer      NOT NULL DEFAULT 0,
    last_error   text
);

CREATE INDEX ix_outbox_event_pending ON outbox_event (seq) WHERE published_at IS NULL;
CREATE INDEX ix_outbox_event_published_at ON outbox_event (published_at) WHERE published_at IS NOT NULL;

CREATE TABLE processed_event (
    consumer     varchar(100) NOT NULL,
    event_id     uuid         NOT NULL,
    processed_at timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);
