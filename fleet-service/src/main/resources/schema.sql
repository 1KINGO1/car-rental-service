CREATE TABLE IF NOT EXISTS vehicles (
    id UUID PRIMARY KEY,
    brand VARCHAR(80) NOT NULL,
    model VARCHAR(80) NOT NULL,
    plate VARCHAR(20) NOT NULL UNIQUE,
    daily_rate NUMERIC(10,2) NOT NULL CHECK (daily_rate > 0)
);
