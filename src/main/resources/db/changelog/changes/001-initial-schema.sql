--liquibase formatted sql

--changeset seat-reservation:001-initial-schema
CREATE TABLE shows (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    price_paise BIGINT NOT NULL,
    per_user_limit INTEGER NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE seats (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL REFERENCES shows(id),
    seat_label VARCHAR(64) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_seats_show_label ON seats(show_id, seat_label);

CREATE TABLE reservations (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL REFERENCES shows(id),
    user_id VARCHAR(128) NOT NULL,
    status VARCHAR(20) NOT NULL,
    amount_paise BIGINT NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ
);
CREATE INDEX idx_reservations_show_user ON reservations(show_id, user_id);

CREATE TABLE reservation_seats (
    id UUID PRIMARY KEY,
    reservation_id UUID NOT NULL REFERENCES reservations(id),
    seat_id UUID NOT NULL REFERENCES seats(id),
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_reservation_seats_reservation ON reservation_seats(reservation_id);
CREATE INDEX idx_reservation_seats_seat ON reservation_seats(seat_id);

CREATE TABLE idempotency_keys (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL REFERENCES shows(id),
    user_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    reservation_id UUID NOT NULL REFERENCES reservations(id),
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_idempotency_lookup ON idempotency_keys(show_id, user_id, idempotency_key);
--rollback DROP TABLE idempotency_keys; DROP TABLE reservation_seats; DROP TABLE reservations; DROP TABLE seats; DROP TABLE shows;
