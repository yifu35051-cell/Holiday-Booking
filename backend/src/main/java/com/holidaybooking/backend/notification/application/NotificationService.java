package com.holidaybooking.backend.notification.application;

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

/** 预订通知与入住前一天提醒。业务事务内创建任务，事务外投递，并记录可恢复的处理状态。 */
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
                        + "\n已付金额：CNY "
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
        // 按酒店当地日期计算“前一天”，而非简单减去 24 小时。
        Instant due =
                checkIn.minusDays(1)
                        .atTime(settings.getReminderTime())
                        .atZone(settings.getZone())
                        .toInstant();
        boolean sameDay = !now.atZone(settings.getZone()).toLocalDate().isBefore(checkIn);
        tasks.schedule(
                booking,
                revision,
                "REMINDER_D1",
                recipient,
                "Holiday Booking 入住提醒",
                content,
                due.isBefore(now) ? now : due,
                checkIn.atTime(settings.getCheckInTime()).atZone(settings.getZone()).toInstant(),
                sameDay ? "SKIPPED" : "READY");
    }

    @Scheduled(fixedDelayString = "${booking.job-delay-ms:60000}")
    public void scheduled() {
        if (settings.isJobsEnabled()) {
            dispatch();
        }
    }

    /** 先领取任务并提交租约，再调用邮件服务，最后单独保存结果。 网络请求不持有任务锁；进程崩溃后，超时租约转为 UNKNOWN，等待人工核对。 */
    public void dispatch() {
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
            result = sender.send(task);
        } catch (RuntimeException exception) {
            // 发送异常可能发生在 SMTP 已接受邮件之后，不能直接假定邮件未发出。
            result =
                    new EmailSender.Result(
                            EmailSender.Outcome.UNKNOWN, "Unexpected transport failure");
        }

        DeliveryUpdate update = nextDeliveryState(task, result);
        tx.executeWithoutResult(
                status ->
                        tasks.complete(
                                task, update.state(), update.detail(), update.nextAttempt()));
    }

    private record DeliveryUpdate(String state, String detail, Instant nextAttempt) {}

    private DeliveryUpdate nextDeliveryState(
            NotificationRepository.Task task, EmailSender.Result result) {
        String state = result.outcome().name();
        Instant nextAttempt = clock.instant();

        // 首次失败后依次等待 1、5、30 分钟，最多尝试四次。
        if (result.outcome() == EmailSender.Outcome.RETRY) {
            if (task.attempts() >= 4) {
                state = "FAILED";
            } else {
                int delayMinutes = new int[] {1, 5, 30}[task.attempts() - 1];
                nextAttempt = nextAttempt.plus(Duration.ofMinutes(delayMinutes));
            }
        }

        return new DeliveryUpdate(state, result.detail(), nextAttempt);
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
