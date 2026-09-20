CREATE TABLE IF NOT EXISTS bookings (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    vehicle_id UUID NOT NULL,
    vehicle_brand VARCHAR(80) NOT NULL,
    vehicle_model VARCHAR(80) NOT NULL,
    vehicle_plate VARCHAR(20) NOT NULL,
    daily_rate NUMERIC(10,2) NOT NULL CHECK (daily_rate > 0),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    total NUMERIC(14,2) NOT NULL CHECK (total > 0),
    customer_verified BOOLEAN NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status = 'REQUESTED'),
    CHECK (end_date > start_date AND end_date <= start_date + 365)
);
