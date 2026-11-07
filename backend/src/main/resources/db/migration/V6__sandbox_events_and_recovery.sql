-- Callback intent is committed atomically with the wallet debit. No WireMock state is authoritative.
CREATE TABLE sandbox_payment_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    out_trade_no VARCHAR(64) NOT NULL UNIQUE,
    state VARCHAR(16) NOT NULL DEFAULT 'READY',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    lease_until DATETIME(6) NULL,
    source_trace VARCHAR(64) NULL,
    CONSTRAINT ck_sandbox_event_state CHECK (state IN ('READY','SENDING','SENT','REVIEW')),
    CONSTRAINT ck_sandbox_event_attempts CHECK (attempts>=0),
    FOREIGN KEY(out_trade_no) REFERENCES mock_payment(out_trade_no),
    INDEX ix_sandbox_event_due (state, next_attempt_at)
);

-- Shared recovery leases prevent concurrent workers from hammering an unavailable provider.
CREATE TABLE integration_recovery (
    operation_key VARCHAR(120) PRIMARY KEY,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    lease_until DATETIME(6) NULL,
    manual_review BOOLEAN NOT NULL DEFAULT FALSE,
    source_trace VARCHAR(64) NULL,
    INDEX ix_recovery_due (manual_review, next_attempt_at)
);

ALTER TABLE notification_task ADD COLUMN source_trace VARCHAR(64) NULL;
ALTER TABLE mail_message ADD COLUMN source_trace VARCHAR(64) NULL;
