package com.holidaybooking.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.holidaybooking.backend.booking.application.BookingService;
import com.holidaybooking.backend.booking.domain.Stay;
import com.holidaybooking.backend.catalog.application.CatalogService;
import com.holidaybooking.backend.identity.application.IdentityService;
import com.holidaybooking.backend.notification.application.NotificationService;
import com.holidaybooking.backend.notification.domain.EmailSender;
import com.holidaybooking.backend.payment.application.PaymentService;
import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.shared.infrastructure.JdbcTime;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 验证通知队列在重试、未知投递和工作进程中断后的恢复规则。 */
@SpringBootTest(
        properties =
                "spring.datasource.url=jdbc:h2:mem:notifications;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class NotificationDeliveryTest {
    @MockitoBean private EmailSender sender;
    @Autowired private IdentityService identity;
    @Autowired private CatalogService catalog;
    @Autowired private BookingService bookings;
    @Autowired private PaymentService payments;
    @Autowired private NotificationService notifications;
    @Autowired private JdbcClient jdbc;
    @Autowired private TestClockConfig.MutableClock clock;
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
    void retriesUseBoundedBackoffAndStopAfterFourAttempts() {
        when(sender.send(any()))
                .thenReturn(
                        new EmailSender.Result(
                                EmailSender.Outcome.RETRY, "Temporary authentication failure"));
        notifications.dispatch();
        assertThat(state()).isEqualTo("RETRY");
        notifications.dispatch();
        verify(sender, times(1)).send(any());

        for (int minutes : new int[] {1, 5, 30}) {
            clock.advance(Duration.ofMinutes(minutes));
            notifications.dispatch();
        }
        assertThat(state()).isEqualTo("FAILED");
        verify(sender, times(4)).send(any());
    }

    @Test
    void uncertainDeliveryRequiresExplicitAcknowledgementBeforeRetry() {
        when(sender.send(any()))
                .thenReturn(
                        new EmailSender.Result(
                                EmailSender.Outcome.UNKNOWN, "SMTP outcome unknown"));
        notifications.dispatch();
        assertThat(state()).isEqualTo("UNKNOWN");
        clock.advance(Duration.ofHours(1));
        notifications.dispatch();
        verify(sender, times(1)).send(any());
        assertThatThrownBy(() -> notifications.retry(userId, taskId, false))
                .isInstanceOf(DomainException.class);

        notifications.retry(userId, taskId, true);
        when(sender.send(any()))
                .thenReturn(new EmailSender.Result(EmailSender.Outcome.SENT, "SMTP accepted"));
        notifications.dispatch();
        assertThat(state()).isEqualTo("SENT");
    }

    @Test
    void expiredWorkerLeaseIsNotAutomaticallyResent() {
        jdbc.sql(
                        "update notification_task set"
                                + " state='SENDING',lease_token='old-worker',lease_until=? where id=?")
                .params(JdbcTime.write(clock.instant().minusSeconds(1)), taskId)
                .update();
        notifications.dispatch();
        assertThat(state()).isEqualTo("UNKNOWN");
        verifyNoInteractions(sender);
    }

    @Test
    void reminderIsSkippedAfterArrivalInsteadOfSendingStaleMail() {
        jdbc.sql("update notification_task set state='SENT' where id=?").param(taskId).update();
        clock.advance(Duration.ofDays(20));
        notifications.dispatch();
        assertThat(
                        jdbc.sql(
                                        "select state from notification_task where booking_id=? and"
                                                + " task_type='REMINDER_D1'")
                                .param(bookingId)
                                .query(String.class)
                                .single())
                .isEqualTo("SKIPPED");
        verifyNoInteractions(sender);
    }
}
