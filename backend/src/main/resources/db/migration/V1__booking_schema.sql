-- MySQL 8.4 production schema; also exercised in H2 MySQL mode for fast tests.
-- All DATETIME columns store UTC. Local stay dates are interpreted in Asia/Shanghai.

CREATE TABLE user_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    role VARCHAR(20) NOT NULL,
    enabled BOOLEAN NOT NULL,
    CONSTRAINT ck_user_role CHECK (role IN ('CUSTOMER','STAFF','ADMIN'))
);

CREATE TABLE membership_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    name VARCHAR(100) NOT NULL,
    fee DECIMAL(12,2) NOT NULL,
    discount_ratio DECIMAL(5,4) NOT NULL,
    duration_days INT NOT NULL,
    active BOOLEAN NOT NULL,
    CONSTRAINT ck_plan_amount CHECK(fee>0 AND discount_ratio>0 AND discount_ratio<1 AND duration_days>0)
);

CREATE TABLE membership (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    user_id BIGINT NOT NULL UNIQUE,
    plan_id BIGINT NOT NULL,
    valid_until DATETIME(6) NOT NULL,
    discount_ratio DECIMAL(5,4) NOT NULL,
    FOREIGN KEY(user_id) REFERENCES user_account(id),
    FOREIGN KEY(plan_id) REFERENCES membership_plan(id)
);

CREATE TABLE membership_purchase (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    user_id BIGINT NOT NULL,
    plan_id BIGINT NOT NULL,
    fee DECIMAL(12,2) NOT NULL,
    discount_ratio DECIMAL(5,4) NOT NULL,
    duration_days INT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    status VARCHAR(30) NOT NULL,
    FOREIGN KEY(user_id) REFERENCES user_account(id),
    FOREIGN KEY(plan_id) REFERENCES membership_plan(id),
    CONSTRAINT ck_purchase_status CHECK(status IN ('PENDING_PAYMENT','ACTIVE','EXPIRED'))
);

CREATE TABLE room_type (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    capacity INT NOT NULL,
    base_price DECIMAL(12,2) NOT NULL,
    active BOOLEAN NOT NULL,
    CONSTRAINT ck_room_type_capacity CHECK(capacity BETWEEN 1 AND 20 AND base_price>0)
);

CREATE TABLE room (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    room_type_id BIGINT NOT NULL,
    number VARCHAR(30) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL,
    FOREIGN KEY(room_type_id) REFERENCES room_type(id)
);

CREATE TABLE room_rate (
    room_type_id BIGINT NOT NULL,
    stay_date DATE NOT NULL,
    price DECIMAL(12,2) NOT NULL,
    PRIMARY KEY(room_type_id,stay_date),
    FOREIGN KEY(room_type_id) REFERENCES room_type(id),
    CONSTRAINT ck_rate_price CHECK(price>0)
);

CREATE TABLE booking_quote (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    user_id BIGINT NOT NULL,
    snapshot TEXT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6),
    FOREIGN KEY(user_id) REFERENCES user_account(id)
);

CREATE TABLE booking (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    user_id BIGINT NOT NULL,
    quote_id BIGINT NOT NULL UNIQUE,
    check_in DATE NOT NULL,
    check_out DATE NOT NULL,
    total_amount DECIMAL(12,2) NOT NULL,
    contact_name VARCHAR(80) NOT NULL,
    contact_email VARCHAR(254) NOT NULL,
    contact_phone VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    confirmed_at DATETIME(6),
    current_revision INT NOT NULL DEFAULT 1,
    FOREIGN KEY(user_id) REFERENCES user_account(id),
    FOREIGN KEY(quote_id) REFERENCES booking_quote(id),
    CONSTRAINT ck_booking_stay CHECK(check_out>check_in AND total_amount>0),
    CONSTRAINT ck_booking_status CHECK(status IN ('PENDING_PAYMENT','CONFIRMED','EXPIRED'))
);

CREATE TABLE booking_revision (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    revision_number INT NOT NULL,
    discount_ratio DECIMAL(5,4) NOT NULL,
    total_amount DECIMAL(12,2) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE(booking_id,revision_number),
    FOREIGN KEY(booking_id) REFERENCES booking(id)
);

CREATE TABLE booking_room (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    revision_id BIGINT NOT NULL,
    room_id BIGINT NOT NULL,
    room_number VARCHAR(30) NOT NULL,
    room_type_id BIGINT NOT NULL,
    room_type_name VARCHAR(100) NOT NULL,
    guests INT NOT NULL,
    total_amount DECIMAL(12,2) NOT NULL,
    UNIQUE(revision_id,room_id),
    FOREIGN KEY(revision_id) REFERENCES booking_revision(id),
    FOREIGN KEY(room_id) REFERENCES room(id),
    FOREIGN KEY(room_type_id) REFERENCES room_type(id),
    CONSTRAINT ck_booking_room_guests CHECK(guests>0)
);

CREATE TABLE booking_nightly_price (
    booking_room_id BIGINT NOT NULL,
    stay_date DATE NOT NULL,
    base_price DECIMAL(12,2) NOT NULL,
    payable_price DECIMAL(12,2) NOT NULL,
    PRIMARY KEY(booking_room_id,stay_date),
    FOREIGN KEY(booking_room_id) REFERENCES booking_room(id),
    CONSTRAINT ck_nightly_amount CHECK(base_price>0 AND payable_price>0)
);

CREATE TABLE room_block (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    released BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY(room_id) REFERENCES room(id),
    FOREIGN KEY(created_by) REFERENCES user_account(id),
    CONSTRAINT ck_block_range CHECK(end_date>start_date)
);

CREATE TABLE room_night (
    room_id BIGINT NOT NULL,
    stay_date DATE NOT NULL,
    state VARCHAR(10) NOT NULL,
    booking_id BIGINT,
    block_id BIGINT,
    PRIMARY KEY(room_id,stay_date),
    FOREIGN KEY(room_id) REFERENCES room(id),
    FOREIGN KEY(booking_id) REFERENCES booking(id),
    FOREIGN KEY(block_id) REFERENCES room_block(id),
    CONSTRAINT ck_night_owner CHECK((state IN ('HELD','BOOKED') AND booking_id IS NOT NULL AND block_id IS NULL) OR (state='BLOCKED' AND block_id IS NOT NULL AND booking_id IS NULL))
);

CREATE TABLE payment_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    user_id BIGINT NOT NULL,
    booking_id BIGINT UNIQUE,
    membership_purchase_id BIGINT UNIQUE,
    amount DECIMAL(12,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    status VARCHAR(20) NOT NULL,
    successful_attempt_id BIGINT,
    FOREIGN KEY(user_id) REFERENCES user_account(id),
    FOREIGN KEY(booking_id) REFERENCES booking(id),
    FOREIGN KEY(membership_purchase_id) REFERENCES membership_purchase(id),
    CONSTRAINT ck_payment_business CHECK((booking_id IS NOT NULL AND membership_purchase_id IS NULL) OR (booking_id IS NULL AND membership_purchase_id IS NOT NULL)),
    CONSTRAINT ck_payment_amount CHECK(amount>0 AND currency='CNY'),
    CONSTRAINT ck_payment_status CHECK(status IN ('PENDING','SUCCEEDED','EXPIRED'))
);

CREATE TABLE payment_attempt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    payment_order_id BIGINT NOT NULL,
    out_trade_no VARCHAR(64) NOT NULL UNIQUE,
    method VARCHAR(10) NOT NULL,
    provider VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    provider_trade_no VARCHAR(128),
    checkout_url VARCHAR(2048),
    paid_at DATETIME(6),
    FOREIGN KEY(payment_order_id) REFERENCES payment_order(id),
    UNIQUE(provider,provider_trade_no),
    CONSTRAINT ck_payment_method CHECK(method IN ('Alipay','Wechat')),
    CONSTRAINT ck_attempt_status CHECK(status IN ('CREATED','PENDING','UNKNOWN','SUCCEEDED','CLOSED','EXCEPTION'))
);

CREATE TABLE payment_exception (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_order_id BIGINT NOT NULL,
    attempt_id BIGINT NOT NULL UNIQUE,
    reason VARCHAR(100) NOT NULL,
    received_amount DECIMAL(12,2),
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    reviewed_by BIGINT,
    review_note VARCHAR(1000),
    reviewed_at DATETIME(6),
    FOREIGN KEY(payment_order_id) REFERENCES payment_order(id),
    FOREIGN KEY(attempt_id) REFERENCES payment_attempt(id),
    FOREIGN KEY(reviewed_by) REFERENCES user_account(id)
);

CREATE TABLE mock_payment (
    out_trade_no VARCHAR(64) PRIMARY KEY,
    method VARCHAR(10) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    state VARCHAR(20) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    trade_no VARCHAR(128),
    paid_at DATETIME(6),
    FOREIGN KEY(out_trade_no) REFERENCES payment_attempt(out_trade_no)
);

CREATE TABLE notification_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    revision_number INT NOT NULL,
    task_type VARCHAR(30) NOT NULL,
    recipient VARCHAR(254) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    due_at DATETIME(6) NOT NULL,
    send_before DATETIME(6),
    state VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    lease_token VARCHAR(36),
    lease_until DATETIME(6),
    last_error VARCHAR(1000),
    UNIQUE(booking_id,revision_number,task_type),
    FOREIGN KEY(booking_id,revision_number) REFERENCES booking_revision(booking_id,revision_number),
    CONSTRAINT ck_notification_state CHECK(state IN ('READY','RETRY','SENDING','SENT','SIMULATED','UNKNOWN','FAILED','SKIPPED'))
);

CREATE TABLE notification_quota (
    id INT PRIMARY KEY,
    budget_date DATE NOT NULL,
    used_count INT NOT NULL
);

CREATE TABLE idempotency_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    operation VARCHAR(50) NOT NULL,
    request_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    resource_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE(actor_id,operation,request_key),
    FOREIGN KEY(actor_id) REFERENCES user_account(id)
);

CREATE TABLE audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT,
    action VARCHAR(60) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id BIGINT NOT NULL,
    detail VARCHAR(1000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY(actor_id) REFERENCES user_account(id)
);

ALTER TABLE payment_order ADD CONSTRAINT fk_successful_attempt FOREIGN KEY(successful_attempt_id) REFERENCES payment_attempt(id);
CREATE INDEX ix_room_type_active ON room(room_type_id,active);
CREATE INDEX ix_booking_owner ON booking(user_id,created_at);
CREATE INDEX ix_booking_arrival ON booking(check_in,status);
CREATE INDEX ix_room_night_booking ON room_night(booking_id);
CREATE INDEX ix_payment_pending ON payment_order(status,expires_at);
CREATE INDEX ix_attempt_order ON payment_attempt(payment_order_id);
CREATE INDEX ix_notification_due ON notification_task(state,due_at);
CREATE INDEX ix_quote_owner_expiry ON booking_quote(user_id,expires_at);
INSERT INTO notification_quota(id,budget_date,used_count) VALUES(1,'1970-01-01',0);
INSERT INTO membership_plan(version,created_at,updated_at,name,fee,discount_ratio,duration_days,active)
VALUES(0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'标准年度会员',199.00,0.9000,365,TRUE);
