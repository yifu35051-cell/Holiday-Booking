package com.holidaybooking.backend.shared.domain;

import java.time.Instant;
import java.util.List;

/** Persistent automatic recovery budget, distinct from business payment/refund state. */
public interface RecoveryRepository {
    boolean claim(String key, Instant now);

    void complete(String key, boolean terminal, boolean healthy, Instant now);

    record Status(String key, int failures, Instant nextAttemptAt, boolean manualReview) {}

    List<Status> review();

    void release(String key, Instant now);
}
