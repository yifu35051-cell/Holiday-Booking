package com.holidaybooking.backend.mail.infrastructure;

import com.holidaybooking.backend.mail.domain.MailRepository;
import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.shared.infrastructure.JdbcTime;
import java.sql.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** SQL过滤邮箱所有者；个人删除不影响对方邮箱，也不删除管理员审计视图。 */
@Repository
@RequiredArgsConstructor
public class JdbcMailRepository implements MailRepository {
    private final JdbcClient jdbc;

    private static final String MAIL =
            """
            select concat('M',m.id) as mail_id,'PERSONAL' as kind,m.sender_id,m.recipient_id,
            m.sender_email,m.recipient_email,m.subject,m.body,
            case when m.reply_to_id is null then null else concat('M',m.reply_to_id) end as reply_to_id,
            m.created_at,m.delivered_at,m.read_at,m.sender_deleted,m.recipient_deleted,m.state,m.attempts,m.last_error
            from mail_message m
            union all
            select concat('N',n.id),'SYSTEM',null,u.id,'SYSTEM',n.recipient,n.subject,n.body,null,
            n.created_at,n.delivered_at,
            r.read_at,false,coalesce(r.deleted,false),n.state,n.attempts,n.last_error
            from notification_task n left join user_account u on u.email=lower(trim(n.recipient))
            left join mail_notification_receipt r on r.task_id=n.id and r.user_id=u.id
            """;

    private Message map(ResultSet rs, int row) throws SQLException {
        return new Message(
                rs.getString("mail_id"),
                rs.getString("kind"),
                rs.getObject("sender_id", Long.class),
                rs.getObject("recipient_id", Long.class),
                rs.getString("sender_email"),
                rs.getString("recipient_email"),
                rs.getString("subject"),
                rs.getString("body"),
                rs.getString("reply_to_id"),
                JdbcTime.read(rs, "created_at"),
                JdbcTime.read(rs, "delivered_at"),
                JdbcTime.read(rs, "read_at"),
                rs.getBoolean("sender_deleted"),
                rs.getBoolean("recipient_deleted"),
                rs.getString("state"),
                rs.getInt("attempts"),
                rs.getString("last_error"));
    }

    public long create(
            long sender,
            long recipient,
            String senderEmail,
            String recipientEmail,
            String subject,
            String body,
            Long replyTo,
            Instant now) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(
                        "insert into mail_message(sender_id,recipient_id,sender_email,recipient_email,subject,body,reply_to_id,created_at,due_at) values(?,?,?,?,?,?,?,?,?)")
                .params(
                        sender,
                        recipient,
                        senderEmail,
                        recipientEmail,
                        subject,
                        body,
                        replyTo,
                        JdbcTime.write(now),
                        JdbcTime.write(now))
                .update(keys, "id");
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    public Message get(String id) {
        return jdbc.sql("select * from (" + MAIL + ") mail where mail_id=?")
                .param(id)
                .query(this::map)
                .optional()
                .orElseThrow(DomainException::missing);
    }

    private String predicate(Filter filter, Map<String, Object> args) {
        String where = " where 1=1";
        if (filter.user() != null) {
            args.put("user", filter.user());
            where +=
                    switch (filter.folder()) {
                        case INBOX ->
                                " and recipient_id=:user and delivered_at is not null and recipient_deleted=false";
                        case SENT -> " and sender_id=:user and sender_deleted=false";
                        case TRASH ->
                                " and ((recipient_id=:user and delivered_at is not null and recipient_deleted=true) or (sender_id=:user and sender_deleted=true))";
                        case ALL -> throw DomainException.invalid("MAIL_FOLDER", "普通邮箱不支持ALL");
                    };
        }
        if (filter.unreadOnly()) where += " and read_at is null";
        if (filter.senderEmail() != null) {
            where += " and sender_email=:sender";
            args.put("sender", filter.senderEmail());
        }
        if (filter.recipientEmail() != null) {
            where += " and recipient_email=:recipient";
            args.put("recipient", filter.recipientEmail());
        }
        if (filter.state() != null) {
            where += " and state=:state";
            args.put("state", filter.state());
        }
        return where;
    }

    public List<Message> list(Filter filter, int offset, int size) {
        var args = new HashMap<String, Object>();
        String where = predicate(filter, args);
        args.put("size", size);
        args.put("offset", offset);
        return jdbc.sql(
                        "select * from ("
                                + MAIL
                                + ") mail"
                                + where
                                + " order by created_at desc,mail_id desc limit :size offset :offset")
                .params(args)
                .query(this::map)
                .list();
    }

    public long count(Filter filter) {
        var args = new HashMap<String, Object>();
        String where = predicate(filter, args);
        return jdbc.sql("select count(*) from (" + MAIL + ") mail" + where)
                .params(args)
                .query(Long.class)
                .single();
    }

    public void read(Message message, long user, boolean read, Instant now) {
        long id = Long.parseLong(message.id().substring(1));
        if (message.kind().equals("PERSONAL")) {
            jdbc.sql("update mail_message set read_at=? where id=? and recipient_id=?")
                    .params(read ? JdbcTime.write(now) : null, id, user)
                    .update();
        } else {
            receipt(id, user);
            jdbc.sql("update mail_notification_receipt set read_at=? where task_id=? and user_id=?")
                    .params(read ? JdbcTime.write(now) : null, id, user)
                    .update();
        }
    }

    private void receipt(long id, long user) {
        jdbc.sql(
                        "insert into mail_notification_receipt(task_id,user_id) values(?,?) on duplicate key update task_id=task_id")
                .params(id, user)
                .update();
    }

    public void trash(Message message, long user, Folder folder, boolean deleted) {
        long id = Long.parseLong(message.id().substring(1));
        if (message.kind().equals("SYSTEM")) {
            receipt(id, user);
            jdbc.sql("update mail_notification_receipt set deleted=? where task_id=? and user_id=?")
                    .params(deleted, id, user)
                    .update();
        } else {
            String column = folder == Folder.SENT ? "sender_deleted" : "recipient_deleted";
            jdbc.sql("update mail_message set " + column + "=? where id=?")
                    .params(deleted, id)
                    .update();
        }
    }

    public int sentSince(long sender, Instant since) {
        return jdbc.sql("select count(*) from mail_message where sender_id=? and created_at>=?")
                .params(sender, JdbcTime.write(since))
                .query(Integer.class)
                .single();
    }

    public void retry(long id, Instant now, boolean acknowledgeUnknown) {
        String states = acknowledgeUnknown ? "('FAILED','UNKNOWN')" : "('FAILED')";
        if (jdbc.sql(
                                "update mail_message set state='READY',attempts=0,due_at=?,lease_token=null,lease_until=null where id=? and state in "
                                        + states)
                        .params(JdbcTime.write(now), id)
                        .update()
                == 0) {
            throw DomainException.conflict("MAIL_NOT_RETRYABLE", "仅失败邮件可重试；UNKNOWN须确认可能重复投递");
        }
    }

    public List<Long> due(Instant now, int limit) {
        return jdbc.sql(
                        "select id from mail_message where state in ('READY','RETRY') and due_at<=? order by due_at,id limit ?")
                .params(JdbcTime.write(now), limit)
                .query(Long.class)
                .list();
    }

    public Work claim(long id, Instant now, LocalDate date, int dailyLimit) {
        var state =
                jdbc.sql("select state from mail_message where id=? and due_at<=? for update")
                        .params(id, JdbcTime.write(now))
                        .query(String.class)
                        .optional()
                        .orElse("");
        if (!state.equals("READY") && !state.equals("RETRY")) return null;
        // 和预订提醒共享全局SMTP预算，避免两个任务各使用一份额度。
        var budget =
                jdbc.sql(
                                "select budget_date,used_count from notification_quota where id=1 for update")
                        .query((rs, row) -> Map.entry(rs.getDate(1).toLocalDate(), rs.getInt(2)))
                        .single();
        int used = budget.getKey().equals(date) ? budget.getValue() : 0;
        if (used >= dailyLimit) return null;
        jdbc.sql("update notification_quota set budget_date=?,used_count=? where id=1")
                .params(date, used + 1)
                .update();
        String lease = UUID.randomUUID().toString();
        jdbc.sql(
                        "update mail_message set state='SENDING',attempts=attempts+1,lease_token=?,lease_until=? where id=?")
                .params(lease, JdbcTime.write(now.plusSeconds(120)), id)
                .update();
        return jdbc.sql("select * from mail_message where id=?")
                .param(id)
                .query(
                        (rs, row) ->
                                new Work(
                                        id,
                                        rs.getString("sender_email"),
                                        rs.getString("recipient_email"),
                                        rs.getString("subject"),
                                        rs.getString("body"),
                                        rs.getInt("attempts"),
                                        lease))
                .single();
    }

    public void complete(
            Work work, String state, String detail, Instant next, Instant deliveredAt) {
        jdbc.sql(
                        "update mail_message set state=?,last_error=?,due_at=?,delivered_at=?,lease_token=null,lease_until=null where id=? and state='SENDING' and lease_token=?")
                .params(
                        state,
                        detail,
                        JdbcTime.write(next),
                        deliveredAt == null ? null : JdbcTime.write(deliveredAt),
                        work.id(),
                        work.leaseToken())
                .update();
    }

    public void recover(Instant now) {
        jdbc.sql(
                        "update mail_message set state='UNKNOWN',last_error='SMTP lease expired; delivery may have happened' where state='SENDING' and lease_until<?")
                .param(JdbcTime.write(now))
                .update();
    }
}
