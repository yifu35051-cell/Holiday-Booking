-- Additive migration: keep the existing paid order and every historical revision.
ALTER TABLE booking DROP CHECK ck_booking_status;
ALTER TABLE booking ADD CONSTRAINT ck_booking_status
    CHECK(status IN ('PENDING_PAYMENT','CONFIRMED','EXPIRED','CANCELLED'));

CREATE TABLE booking_change (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    revision_number INT NOT NULL,
    kind VARCHAR(10) NOT NULL,
    state VARCHAR(25) NOT NULL,
    quote_id BIGINT,
    delta DECIMAL(12,2) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY(booking_id) REFERENCES booking(id),
    FOREIGN KEY(quote_id) REFERENCES booking_quote(id),
    CONSTRAINT ck_change_kind CHECK(kind IN ('CANCEL','AMEND')),
    CONSTRAINT ck_change_state CHECK(state IN ('QUOTED','AWAITING_PAYMENT','APPLIED','EXPIRED'))
);
CREATE INDEX ix_change_booking ON booking_change(booking_id,state);
CREATE TABLE booking_change_room (
    change_id BIGINT NOT NULL,
    line_number INT NOT NULL,
    room_id BIGINT NOT NULL,
    room_number VARCHAR(30) NOT NULL,
    PRIMARY KEY(change_id,line_number),
    UNIQUE(change_id,room_id),
    FOREIGN KEY(change_id) REFERENCES booking_change(id),
    FOREIGN KEY(room_id) REFERENCES room(id)
);
ALTER TABLE room_night ADD COLUMN change_id BIGINT;
ALTER TABLE room_night ADD CONSTRAINT fk_night_change FOREIGN KEY(change_id) REFERENCES booking_change(id);

ALTER TABLE payment_order ADD COLUMN booking_change_id BIGINT UNIQUE;
ALTER TABLE payment_order ADD CONSTRAINT fk_order_change FOREIGN KEY(booking_change_id) REFERENCES booking_change(id);
ALTER TABLE payment_order DROP CHECK ck_payment_business;
ALTER TABLE payment_order ADD CONSTRAINT ck_payment_business CHECK(
    (booking_id IS NOT NULL AND membership_purchase_id IS NULL AND booking_change_id IS NULL) OR
    (booking_id IS NULL AND membership_purchase_id IS NOT NULL AND booking_change_id IS NULL) OR
    (booking_id IS NULL AND membership_purchase_id IS NULL AND booking_change_id IS NOT NULL));

CREATE TABLE refund_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    change_id BIGINT NOT NULL,
    attempt_id BIGINT NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    request_no VARCHAR(64) NOT NULL UNIQUE,
    last_error VARCHAR(500),
    created_at DATETIME(6) NOT NULL,
    UNIQUE(change_id,attempt_id),
    FOREIGN KEY(change_id) REFERENCES booking_change(id),
    FOREIGN KEY(attempt_id) REFERENCES payment_attempt(id),
    CONSTRAINT ck_refund_amount CHECK(amount>0),
    CONSTRAINT ck_refund_state CHECK(state IN ('PENDING','UNKNOWN','SUCCEEDED'))
);
CREATE INDEX ix_refund_state ON refund_request(state,id);
CREATE TABLE mock_refund (
    request_no VARCHAR(64) PRIMARY KEY,
    out_trade_no VARCHAR(64) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    FOREIGN KEY(out_trade_no) REFERENCES mock_payment(out_trade_no)
);
