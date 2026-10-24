package com.holidaybooking.backend.shared.domain;

import java.time.Instant;
import java.util.List;

/** Durable idempotency and audit ports; callers hold the actor row lock in their transaction. */
public interface OperationStore {
    Long replay(long actor, String operation, String key, String hash);

    void remember(
            long actor, String operation, String key, String hash, long resource, Instant now);

    void audit(
            Long actor,
            String action,
            String resourceType,
            long resourceId,
            String detail,
            Instant now);

    List<AuditEntry> auditEntries(int offset, int size);

    record AuditEntry(
            long id,
            Long actorId,
            String action,
            String resourceType,
            long resourceId,
            String detail,
            Instant createdAt) {}
}
