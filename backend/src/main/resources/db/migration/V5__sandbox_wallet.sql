-- Additive migration: historical mock payments remain valid and are not back-charged.
CREATE TABLE sandbox_wallet (
    user_id BIGINT PRIMARY KEY,
    pin_hash VARCHAR(100),
    balance DECIMAL(14,2) NOT NULL DEFAULT 0,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until DATETIME(6),
    FOREIGN KEY(user_id) REFERENCES user_account(id),
    CONSTRAINT ck_wallet_balance CHECK(balance >= 0),
    CONSTRAINT ck_wallet_failures CHECK(failed_attempts >= 0 AND failed_attempts <= 5)
);

CREATE TABLE sandbox_wallet_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    kind VARCHAR(12) NOT NULL,
    reference_no VARCHAR(128) NOT NULL UNIQUE,
    amount DECIMAL(12,2) NOT NULL,
    balance_after DECIMAL(14,2) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY(user_id) REFERENCES sandbox_wallet(user_id),
    CONSTRAINT ck_wallet_entry_kind CHECK(kind IN ('CREDIT','PAYMENT','REFUND')),
    CONSTRAINT ck_wallet_entry_amount CHECK(
        (kind = 'PAYMENT' AND amount < 0) OR (kind IN ('CREDIT','REFUND') AND amount > 0)),
    CONSTRAINT ck_wallet_entry_balance CHECK(balance_after >= 0)
);
CREATE INDEX ix_wallet_entry_user ON sandbox_wallet_entry(user_id,id);
