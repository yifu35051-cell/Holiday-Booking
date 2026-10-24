package com.holidaybooking.backend.notification.domain;

import java.time.*;
import java.util.*;

/** 通知任务持久化端口；任务领取和完成需独立事务，并以租约令牌防止重复完成。 */
public interface NotificationRepository {
    record Task(
            long id,
            long bookingId,
            int revision,
            String type,
            String recipient,
            String subject,
            String body,
            String state,
            int attempts,
            Instant dueAt,
            String leaseToken,
            String lastError) {}

    void schedule(
            long booking,
            int revision,
            String type,
            String recipient,
            String subject,
            String body,
            Instant due,
            Instant sendBefore,
            String state);

    List<Long> due(Instant now, int limit);

    Task claim(long id, Instant now, LocalDate budgetDate, int dailyLimit);

    void complete(Task task, String state, String detail, Instant nextAttempt);

    void recoverAbandoned(Instant now);

    List<Task> list(int offset, int size);

    void retry(long id, Instant now, boolean acknowledgeUnknown);
}
