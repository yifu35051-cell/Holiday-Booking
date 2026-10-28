-- Personal mail is committed before SMTP delivery. A recipient sees it only after
-- SMTP acceptance (or explicit local log simulation); trash is private per owner.
ALTER TABLE notification_task ADD COLUMN created_at DATETIME(6);
ALTER TABLE notification_task ADD COLUMN delivered_at DATETIME(6);
-- Earlier versions stored completion time in due_at. Historical creation time
-- was not recorded, so leave it NULL instead of inventing a timestamp.
UPDATE notification_task SET delivered_at=due_at WHERE state IN ('SENT','SIMULATED');
CREATE TABLE mail_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sender_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    sender_email VARCHAR(254) NOT NULL,
    recipient_email VARCHAR(254) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    reply_to_id BIGINT,
    created_at DATETIME(6) NOT NULL,
    delivered_at DATETIME(6),
    read_at DATETIME(6),
    sender_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    recipient_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    state VARCHAR(20) NOT NULL DEFAULT 'READY',
    attempts INT NOT NULL DEFAULT 0,
    due_at DATETIME(6) NOT NULL,
    lease_token VARCHAR(36),
    lease_until DATETIME(6),
    last_error VARCHAR(500),
    FOREIGN KEY(sender_id) REFERENCES user_account(id),
    FOREIGN KEY(recipient_id) REFERENCES user_account(id),
    FOREIGN KEY(reply_to_id) REFERENCES mail_message(id),
    CONSTRAINT ck_mail_state CHECK(state IN ('READY','RETRY','SENDING','SENT','SIMULATED','UNKNOWN','FAILED')),
    CONSTRAINT ck_mail_attempts CHECK(attempts>=0)
);
CREATE INDEX ix_mail_inbox ON mail_message(recipient_id,delivered_at,id);
CREATE INDEX ix_mail_sent ON mail_message(sender_id,id);
CREATE INDEX ix_mail_due ON mail_message(state,due_at,id);
CREATE TABLE mail_notification_receipt (
    task_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    read_at DATETIME(6),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY(task_id) REFERENCES notification_task(id) ON DELETE CASCADE,
    FOREIGN KEY(user_id) REFERENCES user_account(id)
);
