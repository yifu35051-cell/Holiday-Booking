package com.holidaybooking.backend.payment;

import static com.holidaybooking.backend.support.TestEvidence.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.holidaybooking.backend.booking.application.BookingService;
import com.holidaybooking.backend.booking.domain.Stay;
import com.holidaybooking.backend.catalog.application.CatalogService;
import com.holidaybooking.backend.identity.application.IdentityService;
import com.holidaybooking.backend.payment.application.PaymentService;
import com.holidaybooking.backend.payment.domain.PaymentGateway;
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

/** 模拟渠道的不同响应；验证业务状态机，而非实际支付宝网络连接。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfig.class)
@Tag("channels")
class PaymentRecoveryTest extends MySqlTestSupport {
    @MockitoBean private PaymentGateway gateway;
    @Autowired private IdentityService identity;
    @Autowired private CatalogService catalog;
    @Autowired private BookingService bookings;
    @Autowired private PaymentService payments;
    @Autowired private JdbcClient jdbc;
    @Autowired private TestClockConfig.MutableClock clock;
    private long userId;
    private BookingService.BookingView booking;
    private PaymentService.AttemptView attempt;

    @BeforeEach
    void preparePendingPayment() {
        clock.reset();
        when(gateway.provider()).thenReturn("ALIPAY_SANDBOX");
        when(gateway.create(any()))
                .thenAnswer(
                        invocation -> {
                            PaymentGateway.Command command = invocation.getArgument(0);
                            return result(
                                    command.outTradeNo(), PaymentGateway.State.PENDING, null, null);
                        });
        userId =
                Long.parseLong(
                        identity.register(
                                        UUID.randomUUID() + "@example.com",
                                        "test-password-123",
                                        "Recovery Guest")
                                .id());
        long typeId =
                Long.parseLong(
                        catalog.createType(userId, "Recovery room", "", 2, new BigDecimal("100.00"))
                                .id());
        catalog.createRoom(userId, typeId, UUID.randomUUID().toString().substring(0, 20));
        var quote =
                bookings.quote(
                        userId,
                        new Stay(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12)),
                        List.of(new BookingService.Selection(typeId, 1, 1)));
        booking =
                bookings.create(
                        userId,
                        Long.parseLong(quote.id()),
                        "Guest",
                        "guest@example.com",
                        "13800000000",
                        UUID.randomUUID().toString());
        attempt =
                payments.start(
                                userId,
                                Long.parseLong(booking.paymentOrderId()),
                                booking.totalAmount(),
                                "Alipay",
                                UUID.randomUUID().toString())
                        .attempts()
                        .getFirst();
    }

    private PaymentGateway.Result result(
            String number, PaymentGateway.State state, BigDecimal amount, Instant paidAt) {
        return new PaymentGateway.Result(number, state, amount, "TRADE-" + number, paidAt, null);
    }

    private long heldNights() {
        return jdbc.sql("select count(*) from room_night where booking_id=? and state='HELD'")
                .param(Long.parseLong(booking.id()))
                .query(Long.class)
                .single();
    }

    @Test
    @CaseSpec(input = "超过15分钟后渠道UNKNOWN；随后确认CLOSED", expected = "未知时保留占房，确认关闭后释放并EXPIRED")
    void unknownExpiredPaymentKeepsInventoryUntilTheProviderConfirmsClosure() {
        var unknown = result(attempt.outTradeNo(), PaymentGateway.State.UNKNOWN, null, null);
        when(gateway.query(any())).thenReturn(unknown);
        when(gateway.close(any())).thenReturn(unknown);
        clock.advance(Duration.ofMinutes(16));
        payments.reconcile(Long.parseLong(booking.paymentOrderId()));
        assertThat(actual(heldNights())).isEqualTo(2);
        assertThat(actual(payments.get(userId, Long.parseLong(booking.paymentOrderId())).status()))
                .isEqualTo("PENDING");

        when(gateway.close(any()))
                .thenReturn(result(attempt.outTradeNo(), PaymentGateway.State.CLOSED, null, null));
        payments.reconcile(Long.parseLong(booking.paymentOrderId()));
        assertThat(actual(heldNights())).isZero();
        assertThat(actual(bookings.get(userId, Long.parseLong(booking.id()), false).status()))
                .isEqualTo("EXPIRED");
    }

    @Test
    @CaseSpec(input = "真实支付时间在截止前，回调在16分钟后到达并重复通知", expected = "预订CONFIRMED；通知仅2条，不重复生成")
    void delayedVerifiedPaymentUsesTheActualPaidTimeAndIsIdempotent() {
        Instant paidAt = clock.instant().plusSeconds(30);
        clock.advance(Duration.ofMinutes(16));
        var paid =
                result(
                        attempt.outTradeNo(),
                        PaymentGateway.State.PAID,
                        new BigDecimal(booking.totalAmount()),
                        paidAt);
        when(gateway.verifyCallback(any())).thenReturn(paid);
        payments.callback(Map.of("signed", "fixture"));
        payments.callback(Map.of("signed", "fixture"));
        assertThat(actual(bookings.get(userId, Long.parseLong(booking.id()), false).status()))
                .isEqualTo("CONFIRMED");
        assertThat(
                        actual(
                                jdbc.sql(
                                                "select count(*) from notification_task where booking_id=?")
                                        .param(Long.parseLong(booking.id()))
                                        .query(Long.class)
                                        .single()))
                .isEqualTo(2);
    }

    @Test
    @CaseSpec(
            input = "应付200元的单收到签名渠道0.01元入账",
            expected = "预订PENDING_PAYMENT；记录AMOUNT_MISMATCH和实际金额0.01")
    void mismatchedIncomingFundsAreRecordedWithoutConfirmingBooking() {
        when(gateway.verifyCallback(any()))
                .thenReturn(
                        result(
                                attempt.outTradeNo(),
                                PaymentGateway.State.PAID,
                                new BigDecimal("0.01"),
                                clock.instant()));
        payments.callback(Map.of("signed", "fixture"));
        assertThat(actual(bookings.get(userId, Long.parseLong(booking.id()), false).status()))
                .isEqualTo("PENDING_PAYMENT");
        assertThat(
                        actual(
                                jdbc.sql("select reason from payment_exception where attempt_id=?")
                                        .param(Long.parseLong(attempt.id()))
                                        .query(String.class)
                                        .single()))
                .isEqualTo("AMOUNT_MISMATCH");
        assertThat(
                        actual(
                                jdbc.sql(
                                                "select received_amount from payment_exception where attempt_id=?")
                                        .param(Long.parseLong(attempt.id()))
                                        .query(BigDecimal.class)
                                        .single()))
                .isEqualByComparingTo("0.01");
    }

    @Test
    @CaseSpec(input = "先关闭过期单并释放库存，再收到迟到付款", expected = "预订仍EXPIRED，库存0，异常LATE_PAYMENT")
    void latePaymentCannotReopenAnExpiredBooking() {
        clock.advance(Duration.ofMinutes(16));
        when(gateway.query(any()))
                .thenReturn(result(attempt.outTradeNo(), PaymentGateway.State.CLOSED, null, null));
        payments.reconcile(Long.parseLong(booking.paymentOrderId()));
        when(gateway.verifyCallback(any()))
                .thenReturn(
                        result(
                                attempt.outTradeNo(),
                                PaymentGateway.State.PAID,
                                new BigDecimal(booking.totalAmount()),
                                clock.instant()));
        payments.callback(Map.of("signed", "fixture"));
        assertThat(actual(bookings.get(userId, Long.parseLong(booking.id()), false).status()))
                .isEqualTo("EXPIRED");
        assertThat(actual(heldNights())).isZero();
        assertThat(
                        actual(
                                jdbc.sql("select reason from payment_exception where attempt_id=?")
                                        .param(Long.parseLong(attempt.id()))
                                        .query(String.class)
                                        .single()))
                .isEqualTo("LATE_PAYMENT");
    }

    @Test
    @CaseSpec(
            input = "模拟收到0.01而应付200.00的回调，管理员查询异常并写入复核说明",
            expected = "异常记录可查询并记为REVIEWED；预订仍PENDING_PAYMENT；不发生退款或强制确认")
    void reviewRecordsAdministrativeDecisionWithoutConfirmingBooking() {
        when(gateway.verifyCallback(any()))
                .thenReturn(
                        result(
                                attempt.outTradeNo(),
                                PaymentGateway.State.PAID,
                                new BigDecimal("0.01"),
                                clock.instant()));
        payments.callback(Map.of("signed", "fixture"));
        long exception =
                jdbc.sql("select id from payment_exception where attempt_id=?")
                        .param(Long.parseLong(attempt.id()))
                        .query(Long.class)
                        .single();
        assertThat(
                        actual(
                                payments.exceptions(0, 100).stream()
                                        .anyMatch(
                                                row ->
                                                        Long.parseLong(row.get("id").toString())
                                                                == exception)))
                .isTrue();
        payments.reviewException(userId, exception, "Manual investigation; no refund requested");
        assertThat(
                        actual(
                                jdbc.sql("select status from payment_exception where id=?")
                                        .param(exception)
                                        .query(String.class)
                                        .single()))
                .isEqualTo("REVIEWED");
        assertThat(actual(bookings.get(userId, Long.parseLong(booking.id()), false).status()))
                .isEqualTo("PENDING_PAYMENT");
    }
}
