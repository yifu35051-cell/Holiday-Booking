package com.holidaybooking.backend.notification.application;

import com.holidaybooking.backend.mail.domain.DeliveryPolicy;
import com.holidaybooking.backend.mail.domain.EmailSender;
import com.holidaybooking.backend.notification.domain.*;
import com.holidaybooking.backend.shared.application.PageResult;
import com.holidaybooking.backend.shared.config.BookingProperties;
import com.holidaybooking.backend.shared.domain.OperationStore;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 预订确认与可配置的 D3/D1 提醒。任务在业务事务内建立，实际发送在事务外完成。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {
    private final NotificationRepository tasks;
    private final EmailSender sender;
    private final BookingProperties settings;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final OperationStore operations;

    /** 与预订确认共享事务；唯一键防止重复生成同一版本的确认邮件或 D1 提醒。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void schedule(
            long booking,
            int revision,
            String recipient,
            String name,
            LocalDate checkIn,
            LocalDate checkOut,
            BigDecimal amount) {
        Instant now = clock.instant();
        String content =
                "您好，"
                        + name
                        + "。\n预订编号："
                        + booking
                        + "\n入住："
                        + checkIn
                        + "\n离店："
                        + checkOut
                        + "\n预订总额：CNY "
                        + amount.toPlainString()
                        + "\n请凭预订信息联系酒店。";
        tasks.schedule(
                booking,
                revision,
                "CONFIRMATION",
                recipient,
                "Holiday Booking 预订确认",
                content,
                now,
                null,
                "READY");
        // 使用当地日历而非固定小时差；新预订已错过的提醒保留为 SKIPPED，避免连发过时邮件。
        // 已存在的 READY 任务停机后仍可补发，直到入住截止时间，由领取逻辑判断。
        for (int days : new LinkedHashSet<>(settings.getReminderDays())) {
            if (days != 1 && days != 3) {
                throw new IllegalStateException("Reminder days must contain only 1 or 3");
            }
            Instant due =
                    checkIn.minusDays(days)
                            .atTime(settings.getReminderTime())
                            .atZone(settings.getZone())
                            .toInstant();
            tasks.schedule(
                    booking,
                    revision,
                    "REMINDER_D" + days,
                    recipient,
                    "Holiday Booking 入住提醒",
                    content,
                    due,
                    checkIn.atTime(settings.getCheckInTime())
                            .atZone(settings.getZone())
                            .toInstant(),
                    due.isBefore(now) ? "SKIPPED" : "READY");
        }
    }

    @Scheduled(fixedDelayString = "${booking.job-delay-ms:60000}")
    public void scheduled() {
        if (settings.isJobsEnabled() && !"disabled".equals(settings.getEmailMode())) {
            dispatch();
        }
    }

    /** 先领取任务并提交租约，再调用邮件服务，最后单独保存结果。 网络请求不持有任务锁；进程崩溃后，超时租约转为 UNKNOWN，等待人工核对。 */
    public void dispatch() {
        if ("disabled".equals(settings.getEmailMode())) return;
        tx.executeWithoutResult(status -> tasks.recoverAbandoned(clock.instant()));
        var dueTaskIds = tx.execute(status -> tasks.due(clock.instant(), 50));
        if (dueTaskIds == null) {
            return;
        }

        for (long taskId : dueTaskIds) {
            try {
                deliverOne(taskId);
            } catch (RuntimeException exception) {
                log.error("Notification processing failed task={}", taskId, exception);
            }
        }
    }

    private void deliverOne(long taskId) {
        Instant now = clock.instant();
        var task =
                tx.execute(
                        status ->
                                tasks.claim(
                                        taskId,
                                        now,
                                        now.atZone(settings.getZone()).toLocalDate(),
                                        settings.getEmailDailyLimit()));
        if (task == null) {
            return;
        }

        EmailSender.Result result;
        try {
            result =
                    sender.sendMessage(
                            new EmailSender.Message(
                                    null, task.recipient(), task.subject(), task.body()));
        } catch (RuntimeException exception) {
            // 发送异常可能发生在 SMTP 已接受邮件之后，不能直接假定邮件未发出。
            result =
                    new EmailSender.Result(
                            EmailSender.Outcome.UNKNOWN, "Unexpected transport failure");
        }

        var update = DeliveryPolicy.next(task.attempts(), result, clock.instant());
        tx.executeWithoutResult(
                status ->
                        tasks.complete(
                                task, update.state(), update.detail(), update.nextAttempt()));
    }

    public record TaskView(
            String id,
            String bookingId,
            int revision,
            String type,
            String recipient,
            String state,
            int attempts,
            Instant dueAt,
            String lastError) {}

    @Transactional(readOnly = true)
    public List<TaskView> list(int page, int size) {
        return tasks.list(PageResult.offset(page, size), size).stream()
                .map(
                        task ->
                                new TaskView(
                                        Long.toString(task.id()),
                                        Long.toString(task.bookingId()),
                                        task.revision(),
                                        task.type(),
                                        task.recipient(),
                                        task.state(),
                                        task.attempts(),
                                        task.dueAt(),
                                        task.lastError()))
                .toList();
    }

    @Transactional
    public void retry(long actor, long id, boolean acknowledgeUnknown) {
        tasks.retry(id, clock.instant(), acknowledgeUnknown);
        operations.audit(
                actor,
                "RETRY_EMAIL",
                "NOTIFICATION",
                id,
                "acknowledgeUnknown=" + acknowledgeUnknown,
                clock.instant());
    }
}
