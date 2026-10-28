package com.holidaybooking.backend.mail.domain;

import java.time.*;
import java.util.List;

/** 邮箱数据和个人邮件出站队列；系统通知直接读取既有通知任务，不重复投递。 */
public interface MailRepository {
    enum Folder {
        INBOX,
        SENT,
        TRASH,
        ALL
    }

    record Message(
            String id,
            String kind,
            Long senderId,
            Long recipientId,
            String senderEmail,
            String recipientEmail,
            String subject,
            String body,
            String replyToId,
            Instant createdAt,
            Instant deliveredAt,
            Instant readAt,
            boolean senderDeleted,
            boolean recipientDeleted,
            String state,
            int attempts,
            String lastError) {}

    record Filter(
            Long user,
            Folder folder,
            boolean unreadOnly,
            String senderEmail,
            String recipientEmail,
            String state) {}

    record Work(
            long id,
            String senderEmail,
            String recipientEmail,
            String subject,
            String body,
            int attempts,
            String leaseToken) {}

    long create(
            long sender,
            long recipient,
            String senderEmail,
            String recipientEmail,
            String subject,
            String body,
            Long replyTo,
            Instant now);

    Message get(String id);

    List<Message> list(Filter filter, int offset, int size);

    long count(Filter filter);

    void read(Message message, long user, boolean read, Instant now);

    void trash(Message message, long user, Folder folder, boolean deleted);

    void retry(long id, Instant now, boolean acknowledgeUnknown);

    int sentSince(long sender, Instant since);

    List<Long> due(Instant now, int limit);

    Work claim(long id, Instant now, LocalDate date, int dailyLimit);

    void complete(Work work, String state, String detail, Instant next, Instant deliveredAt);

    void recover(Instant now);
}
