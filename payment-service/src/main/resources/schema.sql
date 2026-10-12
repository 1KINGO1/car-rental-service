CREATE TABLE IF NOT EXISTS payments (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL UNIQUE,
    amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('UAH','EUR','USD')),
    status VARCHAR(20) NOT NULL CHECK (status = 'PENDING')
);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    request_key VARCHAR(128) PRIMARY KEY,
    fingerprint TEXT NOT NULL,
    result_id UUID REFERENCES payments(id)
);

CREATE TABLE IF NOT EXISTS processed_events (
    event_id UUID PRIMARY KEY,
    booking_id UUID NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS failed_events (
    topic VARCHAR(255) NOT NULL,
    partition_id INTEGER NOT NULL,
    record_offset BIGINT NOT NULL,
    payload TEXT NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (topic, partition_id, record_offset)
);
