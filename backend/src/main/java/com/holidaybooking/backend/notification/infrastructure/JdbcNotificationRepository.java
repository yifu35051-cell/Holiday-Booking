package com.holidaybooking.backend.notification.infrastructure;

import com.holidaybooking.backend.notification.domain.*;
import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.shared.infrastructure.JdbcTime;
import java.sql.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 数据库通知队列。领取任务时检查预订状态、发送截止时间及每日额度。 */
@Repository
@RequiredArgsConstructor
public class JdbcNotificationRepository implements NotificationRepository {
    private final JdbcClient jdbc;

    private Task map(ResultSet rs, int row) throws SQLException {
        return new Task(
                rs.getLong("id"),
                rs.getLong("booking_id"),
                rs.getInt("revision_number"),
                rs.getString("task_type"),
                rs.getString("recipient"),
                rs.getString("subject"),
                rs.getString("body"),
                rs.getString("state"),
                rs.getInt("attempts"),
                JdbcTime.read(rs, "due_at"),
                rs.getString("lease_token"),
                rs.getString("last_error"));
    }

    public void schedule(
            long booking,
            int revision,
            String type,
            String recipient,
            String subject,
            String body,
            Instant due,
            Instant sendBefore,
            String state) {
        jdbc.sql(
                        """
                        insert into notification_task(booking_id,revision_number,task_type,recipient,subject,body,due_at,send_before,state,attempts)
                        values(?,?,?,?,?,?,?,?,?,0)
                        """)
                .params(
                        booking,
                        revision,
                        type,
                        recipient,
                        subject,
                        body,
                        JdbcTime.write(due),
                        sendBefore == null ? null : JdbcTime.write(sendBefore),
                        state)
                .update();
    }

    public List<Long> due(Instant now, int limit) {
        return jdbc.sql(
                        "select id from notification_task where state in ('READY','RETRY') and"
                                + " due_at<=? order by due_at,id limit ?")
                .params(JdbcTime.write(now), limit)
                .query(Long.class)
                .list();
    }

    public Task claim(long id, Instant now, LocalDate budgetDate, int dailyLimit) {
        var task =
                jdbc.sql("select * from notification_task where id=? for update")
                        .param(id)
                        .query(this::map)
                        .optional()
                        .orElse(null);
        if (task == null
                || (!task.state().equals("READY") && !task.state().equals("RETRY"))
                || task.dueAt().isAfter(now)) {
            return null;
        }
        boolean eligible =
                jdbc.sql(
                                        "select count(*) from booking b join notification_task n on"
                                                + " n.booking_id=b.id where n.id=? and"
                                                + " b.status='CONFIRMED' and"
                                                + " b.current_revision=n.revision_number and"
                                                + " (n.send_before is null or n.send_before>?)")
                                .params(task.id(), JdbcTime.write(now))
                                .query(Long.class)
                                .single()
                        > 0;
        if (!eligible) {
            jdbc.sql(
                            "update notification_task set state='SKIPPED',last_error='Booking no"
                                    + " longer eligible' where id=?")
                    .param(id)
                    .update();
            return null;
        }
        var quota =
                jdbc.sql(
                                "select budget_date,used_count from notification_quota where id=1"
                                        + " for update")
                        .query((rs, n) -> Map.entry(rs.getDate(1).toLocalDate(), rs.getInt(2)))
                        .single();
        int used = quota.getKey().equals(budgetDate) ? quota.getValue() : 0;
        if (used >= dailyLimit) {
            return null;
        }
        jdbc.sql("update notification_quota set budget_date=?,used_count=? where id=1")
                .params(budgetDate, used + 1)
                .update();
        String token = UUID.randomUUID().toString();
        jdbc.sql(
                        "update notification_task set"
                                + " state='SENDING',attempts=attempts+1,lease_token=?,lease_until=?"
                                + " where id=?")
                .params(token, JdbcTime.write(now.plusSeconds(120)), id)
                .update();
        return jdbc.sql("select * from notification_task where id=?")
                .param(id)
                .query(this::map)
                .single();
    }

    public void complete(Task task, String state, String detail, Instant nextAttempt) {
        jdbc.sql(
                        "update notification_task set"
                                + " state=?,last_error=?,due_at=?,lease_token=null,lease_until=null"
                                + " where id=? and state='SENDING' and lease_token=?")
                .params(state, detail, JdbcTime.write(nextAttempt), task.id(), task.leaseToken())
                .update();
    }

    public void recoverAbandoned(Instant now) {
        // SMTP may already have accepted the mail: never blindly retry a crashed sender.
        jdbc.sql(
                        "update notification_task set state='UNKNOWN',last_error='Worker lease"
                                + " expired; delivery may have happened' where state='SENDING' and"
                                + " lease_until<?")
                .param(JdbcTime.write(now))
                .update();
    }

    public List<Task> list(int offset, int size) {
        return jdbc.sql("select * from notification_task order by id desc limit ? offset ?")
                .params(size, offset)
                .query(this::map)
                .list();
    }

    public void retry(long id, Instant now, boolean acknowledgeUnknown) {
        String condition = acknowledgeUnknown ? "('FAILED','UNKNOWN')" : "('FAILED')";
        int changed =
                jdbc.sql(
                                "update notification_task set"
                                        + " state='READY',attempts=0,due_at=?,lease_token=null,lease_until=null"
                                        + " where id=? and state in "
                                        + condition)
                        .params(JdbcTime.write(now), id)
                        .update();
        if (changed == 0) {
            throw DomainException.conflict("NOT_RETRYABLE", "仅失败任务可重试；未知状态需要明确确认重复发送风险");
        }
    }
}
