package com.holidaybooking.backend.integration;

import static com.holidaybooking.backend.support.TestEvidence.*;
import static org.assertj.core.api.Assertions.*;

import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.support.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

/** 本地跨模块闭环测试；支付使用模拟渠道，通知只检查持久化任务。 */
@Tag("channels")
public class BusinessFlowTest extends BusinessTestSupport {
    @Test
    @CaseSpec(
            input = "100.05元/晚住2晚，报价后改价999，重复下单，模拟付款并调度通知",
            expected =
                    "快照200.10；同键同ID；支付SUCCEEDED、预订CONFIRMED；2个库存夜、2个任务；确认SIMULATED；D1为11月9日01:00 UTC")
    protected void bookingSnapshotPaymentAndNotificationsFormOneConsistentFlow() {
        long user = user(), type = type(user, 1);
        var quote = quote(user, type);
        assertThat(actual(quote.totalAmount())).isEqualTo("200.10");
        catalog.rates(user, type, stay(), new BigDecimal("999.00"));
        var booking = book(user, Long.parseLong(quote.id()), "booking-key-0001");
        assertThat(actual(booking.totalAmount())).isEqualTo("200.10");
        assertThat(actual(book(user, Long.parseLong(quote.id()), "booking-key-0001").id()))
                .isEqualTo(booking.id());
        assertThatThrownBy(
                        expectFailure(
                                () -> book(user, Long.parseLong(quote.id()), "booking-key-0002")))
                .isInstanceOf(DomainException.class);
        assertThat(
                        actual(
                                pay(user, booking.paymentOrderId(), booking.totalAmount(), "Alipay")
                                        .status()))
                .isEqualTo("SUCCEEDED");
        assertThat(actual(bookings.get(user, Long.parseLong(booking.id()), false).status()))
                .isEqualTo("CONFIRMED");
        payments.refresh(user, Long.parseLong(booking.paymentOrderId()));
        assertThat(
                        actual(
                                jdbc.sql(
                                                "select count(*) from notification_task where booking_id=?")
                                        .param(Long.parseLong(booking.id()))
                                        .query(Long.class)
                                        .single()))
                .isEqualTo(2);
        assertThat(
                        actual(
                                jdbc.sql(
                                                "select count(*) from room_night where booking_id=? and"
                                                        + " state='BOOKED'")
                                        .param(Long.parseLong(booking.id()))
                                        .query(Long.class)
                                        .single()))
                .isEqualTo(2);
        notifications.dispatch();
        assertThat(
                        actual(
                                jdbc.sql(
                                                "select state from notification_task where booking_id=? and"
                                                        + " task_type='CONFIRMATION'")
                                        .param(Long.parseLong(booking.id()))
                                        .query(String.class)
                                        .single()))
                .isEqualTo("SIMULATED");
        assertThat(
                        actual(
                                jdbc.sql(
                                                "select due_at from notification_task where booking_id=?"
                                                        + " and task_type='REMINDER_D1'")
                                        .param(Long.parseLong(booking.id()))
                                        .query(LocalDateTime.class)
                                        .single()))
                .isEqualTo(LocalDateTime.of(2026, 11, 9, 1, 0));
    }
}
