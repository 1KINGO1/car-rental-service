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
