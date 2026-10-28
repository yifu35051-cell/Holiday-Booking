package com.holidaybooking.backend.notification;

import static com.holidaybooking.backend.support.TestEvidence.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.holidaybooking.backend.booking.application.BookingService;
import com.holidaybooking.backend.booking.domain.Stay;
import com.holidaybooking.backend.catalog.application.CatalogService;
import com.holidaybooking.backend.identity.application.IdentityService;
import com.holidaybooking.backend.mail.domain.EmailSender;
import com.holidaybooking.backend.notification.application.NotificationService;
import com.holidaybooking.backend.payment.application.PaymentService;
import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.shared.infrastructure.JdbcTime;
import com.holidaybooking.backend.support.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 验证通知队列在重试、未知投递和工作进程中断后的恢复规则。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfig.class)
@Tag("channels")
class NotificationDeliveryTest extends MySqlTestSupport {
    @MockitoBean private EmailSender sender;
    @Autowired private IdentityService identity;
    @Autowired private CatalogService catalog;
    @Autowired private BookingService bookings;
    @Autowired private PaymentService payments;
    @Autowired private NotificationService notifications;
    @Autowired private JdbcClient jdbc;
    @Autowired private TestClockConfig.MutableClock clock;
    @Autowired private com.holidaybooking.backend.shared.config.BookingProperties settings;
    private long userId;
    private long taskId;
    private long bookingId;

    @BeforeEach
    void createConfirmedBooking() {
        clock.reset();
        jdbc.sql("update notification_task set state='SKIPPED'").update();
        jdbc.sql("update notification_quota set used_count=0").update();
        userId =
                Long.parseLong(
                        identity.register(
                                        UUID.randomUUID() + "@example.com",
                                        "test-password-123",
                                        "Email Guest")
                                .id());
        long type =
                Long.parseLong(
                        catalog.createType(userId, "Email room", "", 2, new BigDecimal("100.00"))
                                .id());
        catalog.createRoom(userId, type, UUID.randomUUID().toString().substring(0, 20));
        var quote =
                bookings.quote(
                        userId,
                        new Stay(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12)),
                        List.of(new BookingService.Selection(type, 1, 1)));
        var booking =
                bookings.create(
                        userId,
                        Long.parseLong(quote.id()),
                        "Guest",
                        "guest@example.com",
                        "13800000000",
                        UUID.randomUUID().toString());
        bookingId = Long.parseLong(booking.id());
        var payment =
                payments.start(
                        userId,
                        Long.parseLong(booking.paymentOrderId()),
                        booking.totalAmount(),
                        "Alipay",
                        UUID.randomUUID().toString());
        payments.simulate(userId, Long.parseLong(payment.attempts().getFirst().id()));
        taskId =
                jdbc.sql(
                                "select id from notification_task where booking_id=? and"
                                        + " task_type='CONFIRMATION'")
                        .param(bookingId)
                        .query(Long.class)
                        .single();
    }

    private String state() {
        return jdbc.sql("select state from notification_task where id=?")
                .param(taskId)
                .query(String.class)
                .single();
    }

    @Test
    @CaseSpec(input = "发送器连续RETRY；推进1、5、30分钟", expected = "立即重复调度不重发；总4次后FAILED")
    void retriesUseBoundedBackoffAndStopAfterFourAttempts() {
        when(sender.sendMessage(any()))
                .thenReturn(
                        new EmailSender.Result(
                                EmailSender.Outcome.RETRY, "Temporary authentication failure"));
        notifications.dispatch();
        assertThat(actual(state())).isEqualTo("RETRY");
        notifications.dispatch();
        verify(sender, times(1)).sendMessage(any());

        for (int minutes : new int[] {1, 5, 30}) {
            clock.advance(Duration.ofMinutes(minutes));
            notifications.dispatch();
        }
        assertThat(actual(state())).isEqualTo("FAILED");
        verify(sender, times(4)).sendMessage(any());
    }

    @Test
    @CaseSpec(
            input = "发送结果UNKNOWN，推进1小时，人工分别不确认/确认重试",
            expected = "UNKNOWN不自动重发；未确认重试拒绝；确认重试后可SENT")
    void uncertainDeliveryRequiresExplicitAcknowledgementBeforeRetry() {
        when(sender.sendMessage(any()))
                .thenReturn(
                        new EmailSender.Result(
                                EmailSender.Outcome.UNKNOWN, "SMTP outcome unknown"));
        notifications.dispatch();
        assertThat(actual(state())).isEqualTo("UNKNOWN");
        clock.advance(Duration.ofHours(1));
        notifications.dispatch();
        verify(sender, times(1)).sendMessage(any());
        assertThatThrownBy(expectFailure(() -> notifications.retry(userId, taskId, false)))
                .isInstanceOf(DomainException.class);

        notifications.retry(userId, taskId, true);
        when(sender.sendMessage(any()))
                .thenReturn(new EmailSender.Result(EmailSender.Outcome.SENT, "SMTP accepted"));
        notifications.dispatch();
        assertThat(actual(state())).isEqualTo("SENT");
    }

    @Test
    @CaseSpec(input = "任务SENDING且租约已过期", expected = "转UNKNOWN，不调用发送器")
    void expiredWorkerLeaseIsNotAutomaticallyResent() {
        jdbc.sql(
                        "update notification_task set"
                                + " state='SENDING',lease_token='old-worker',lease_until=? where id=?")
                .params(JdbcTime.write(clock.instant().minusSeconds(1)), taskId)
                .update();
        notifications.dispatch();
        assertThat(actual(state())).isEqualTo("UNKNOWN");
        verifyNoInteractions(sender);
    }

    @Test
    @CaseSpec(input = "确认邮件已发送，时钟推进20天到入住之后", expected = "D1为SKIPPED，不调用发送器")
    void reminderIsSkippedAfterArrivalInsteadOfSendingStaleMail() {
        jdbc.sql("update notification_task set state='SENT' where id=?").param(taskId).update();
        clock.advance(Duration.ofDays(20));
        notifications.dispatch();
        assertThat(
                        actual(
                                jdbc.sql(
                                                "select state from notification_task where booking_id=? and"
                                                        + " task_type='REMINDER_D1'")
                                        .param(bookingId)
                                        .query(String.class)
                                        .single()))
                .isEqualTo("SKIPPED");
        verifyNoInteractions(sender);
    }

    @Test
    @CaseSpec(
            input = "当天发送配额已使用50次，再调度一个待发送确认任务",
            expected = "任务保持READY且attempts=0；不调用发送器；后台列表可查该任务")
    void dailyQuotaDefersDeliveryWithoutConsumingAnAttempt() {
        jdbc.sql("update notification_quota set budget_date=?,used_count=50 where id=1")
                .param(clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate())
                .update();
        notifications.dispatch();
        assertThat(actual(state())).isEqualTo("READY");
        assertThat(
                        actual(
                                notifications.list(0, 100).stream()
                                        .filter(t -> t.id().equals(Long.toString(taskId)))
                                        .findFirst()
                                        .orElseThrow()
                                        .attempts()))
                .isZero();
        verifyNoInteractions(sender);
    }

    @Autowired private org.springframework.transaction.support.TransactionTemplate transactions;

    @Test
    @CaseSpec(
            input = "reminderDays=[3,1,3]；入住2026-11-10；创建时间2026-10-22T00:00:00Z",
            expected = "只生成CONFIRMATION、REMINDER_D3、REMINDER_D1各一项；提醒分别在11月7日和9日北京时间09:00")
    void schedulesDistinctD3AndD1AtHotelLocalTime() {
        var previousDays = settings.getReminderDays();
        try {
            settings.setReminderDays(List.of(3, 1, 3));
            rescheduleCurrentBooking();
            var rows =
                    jdbc.sql(
                                    "select task_type,due_at,state from notification_task where booking_id=? order by due_at,task_type")
                            .param(bookingId)
                            .query(
                                    (rs, row) ->
                                            rs.getString("task_type")
                                                    + "|"
                                                    + JdbcTime.read(rs, "due_at")
                                                    + "|"
                                                    + rs.getString("state"))
                            .list();
            assertThat(actual(rows))
                    .containsExactly(
                            "CONFIRMATION|2026-10-22T00:00:00Z|READY",
                            "REMINDER_D3|2026-11-07T01:00:00Z|READY",
                            "REMINDER_D1|2026-11-09T01:00:00Z|READY");
        } finally {
            settings.setReminderDays(previousDays);
        }
    }

    @Test
    @CaseSpec(
            input = "reminderDays=[3,1]；入住2026-11-10；分别在11月9日09:00与09:00:01北京时间创建提醒",
            expected = "09:00时D3=SKIPPED且D1=READY；09:00:01时两项提醒均SKIPPED；确认邮件仍READY")
    void missedRemindersAreSkippedButExactDueTimeRemainsEligible() {
        var previousDays = settings.getReminderDays();
        try {
            settings.setReminderDays(List.of(3, 1));
            clock.advance(Duration.between(clock.instant(), Instant.parse("2026-11-09T01:00:00Z")));
            rescheduleCurrentBooking();
            assertThat(actual(reminderStates()))
                    .containsExactly("REMINDER_D1|READY", "REMINDER_D3|SKIPPED");
            clock.advance(Duration.ofSeconds(1));
            rescheduleCurrentBooking();
            assertThat(actual(reminderStates()))
                    .containsExactly("REMINDER_D1|SKIPPED", "REMINDER_D3|SKIPPED");
            assertThat(
                            actual(
                                    jdbc.sql(
                                                    "select state from notification_task where booking_id=? and task_type='CONFIRMATION'")
                                            .param(bookingId)
                                            .query(String.class)
                                            .single()))
                    .isEqualTo("READY");
        } finally {
            settings.setReminderDays(previousDays);
        }
    }

    private List<String> reminderStates() {
        return jdbc.sql(
                        "select task_type,state from notification_task where booking_id=? and task_type like 'REMINDER_%' order by task_type")
                .param(bookingId)
                .query((rs, row) -> rs.getString(1) + "|" + rs.getString(2))
                .list();
    }

    private void rescheduleCurrentBooking() {
        // 仅替换本用例的任务，保留原预订版本和真实外键。
        transactions.executeWithoutResult(
                status -> {
                    jdbc.sql("delete from notification_task where booking_id=?")
                            .param(bookingId)
                            .update();
                    notifications.schedule(
                            bookingId,
                            1,
                            "guest@example.com",
                            "Guest",
                            LocalDate.of(2026, 11, 10),
                            LocalDate.of(2026, 11, 12),
                            new BigDecimal("200"));
                });
    }

    @Test
    @CaseSpec(
            input = "酒店入住当天08:00，为已存在的预订版本重新生成通知任务（先清理本用例任务）",
            expected = "确认任务READY，D1提醒SKIPPED，不补发前一天提醒")
    void sameDayArrivalSkipsPriorDayReminder() {
        clock.advance(Duration.ofDays(19));
        // 只清理当前用例的任务，保留真实存在的 revision 1，满足数据库外键约束。
        jdbc.sql("delete from notification_task where booking_id=?").param(bookingId).update();
        transactions.executeWithoutResult(
                status ->
                        notifications.schedule(
                                bookingId,
                                1,
                                "guest@example.com",
                                "Guest",
                                LocalDate.of(2026, 11, 10),
                                LocalDate.of(2026, 11, 12),
                                new BigDecimal("200")));
        assertThat(
                        actual(
                                jdbc.sql(
                                                "select state from notification_task where booking_id=? and revision_number=1 and task_type='REMINDER_D1'")
                                        .param(bookingId)
                                        .query(String.class)
                                        .single()))
                .isEqualTo("SKIPPED");
        assertThat(
                        actual(
                                jdbc.sql(
                                                "select state from notification_task where booking_id=? and revision_number=1 and task_type='CONFIRMATION'")
                                        .param(bookingId)
                                        .query(String.class)
                                        .single()))
                .isEqualTo("READY");
    }
}
