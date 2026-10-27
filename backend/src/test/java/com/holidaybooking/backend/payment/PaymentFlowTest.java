package com.holidaybooking.backend.payment;

import static com.holidaybooking.backend.support.TestEvidence.*;
import static org.assertj.core.api.Assertions.*;

import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.support.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

/** 支付尝试与预订超时测试；使用可调整时钟和本地模拟渠道。 */
@Tag("channels")
public class PaymentFlowTest extends BusinessTestSupport {
    @Test
    @CaseSpec(input = "待付预订后推进时钟16分钟，执行未付单恢复", expected = "预订EXPIRED，占房夜数0")
    protected void unpaidExpiryReleasesAllHeldNights() {
        long user = user(), type = type(user, 1);
        var q = quote(user, type);
        var b = book(user, Long.parseLong(q.id()), "expiry-booking-key");
        clock.advance(Duration.ofMinutes(16));
        payments.reconcile(Long.parseLong(b.paymentOrderId()));
        assertThat(actual(bookings.get(user, Long.parseLong(b.id()), false).status()))
                .isEqualTo("EXPIRED");
        assertThat(
                        actual(
                                jdbc.sql("select count(*) from room_night where booking_id=?")
                                        .param(Long.parseLong(b.id()))
                                        .query(Long.class)
                                        .single()))
                .isZero();
    }

    @Test
    @CaseSpec(input = "同一支付尝试键重试，再尝试另一方式及错误金额", expected = "未决尝试仅1条；另一尝试被拒绝；错误金额抛异常")
    protected void paymentRetryIsIdempotentAndDifferentAttemptIsBlocked() {
        long user = user(), type = type(user, 1);
        var b = book(user, Long.parseLong(quote(user, type).id()), "attempt-booking-key");
        long order = Long.parseLong(b.paymentOrderId());
        var p = payments.start(user, order, b.totalAmount(), "Alipay", "same-attempt-key");
        assertThat(
                        actual(
                                payments.start(
                                                user,
                                                order,
                                                b.totalAmount(),
                                                "Alipay",
                                                "same-attempt-key")
                                        .attempts()))
                .hasSize(1);
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        payments.start(
                                                user,
                                                order,
                                                b.totalAmount(),
                                                "Wechat",
                                                "another-attempt-key")))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        payments.start(
                                                user, order, "0.01", "Alipay", "wrong-amount-key")))
                .isInstanceOf(IllegalArgumentException.class);
        payments.simulate(user, Long.parseLong(p.attempts().getFirst().id()));
    }
}
