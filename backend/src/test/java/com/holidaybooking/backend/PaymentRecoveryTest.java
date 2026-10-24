package com.holidaybooking.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.holidaybooking.backend.booking.application.BookingService;
import com.holidaybooking.backend.booking.domain.Stay;
import com.holidaybooking.backend.catalog.application.CatalogService;
import com.holidaybooking.backend.identity.application.IdentityService;
import com.holidaybooking.backend.payment.application.PaymentService;
import com.holidaybooking.backend.payment.domain.PaymentGateway;
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

/** 模拟渠道的不同响应；验证业务状态机，而非实际支付宝网络连接。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class PaymentRecoveryTest {
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
    void unknownExpiredPaymentKeepsInventoryUntilTheProviderConfirmsClosure() {
        var unknown = result(attempt.outTradeNo(), PaymentGateway.State.UNKNOWN, null, null);
        when(gateway.query(any())).thenReturn(unknown);
        when(gateway.close(any())).thenReturn(unknown);
        clock.advance(Duration.ofMinutes(16));
        payments.reconcile(Long.parseLong(booking.paymentOrderId()));
        assertThat(heldNights()).isEqualTo(2);
        assertThat(payments.get(userId, Long.parseLong(booking.paymentOrderId())).status())
                .isEqualTo("PENDING");

        when(gateway.close(any()))
                .thenReturn(result(attempt.outTradeNo(), PaymentGateway.State.CLOSED, null, null));
        payments.reconcile(Long.parseLong(booking.paymentOrderId()));
        assertThat(heldNights()).isZero();
        assertThat(bookings.get(userId, Long.parseLong(booking.id()), false).status())
                .isEqualTo("EXPIRED");
    }

    @Test
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
        assertThat(bookings.get(userId, Long.parseLong(booking.id()), false).status())
                .isEqualTo("CONFIRMED");
        assertThat(
                        jdbc.sql("select count(*) from notification_task where booking_id=?")
                                .param(Long.parseLong(booking.id()))
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
    }

    @Test
    void mismatchedIncomingFundsAreRecordedWithoutConfirmingBooking() {
        when(gateway.verifyCallback(any()))
                .thenReturn(
                        result(
                                attempt.outTradeNo(),
                                PaymentGateway.State.PAID,
                                new BigDecimal("0.01"),
                                clock.instant()));
        payments.callback(Map.of("signed", "fixture"));
        assertThat(bookings.get(userId, Long.parseLong(booking.id()), false).status())
                .isEqualTo("PENDING_PAYMENT");
        assertThat(
                        jdbc.sql("select reason from payment_exception where attempt_id=?")
                                .param(Long.parseLong(attempt.id()))
                                .query(String.class)
                                .single())
                .isEqualTo("AMOUNT_MISMATCH");
        assertThat(
                        jdbc.sql("select received_amount from payment_exception where attempt_id=?")
                                .param(Long.parseLong(attempt.id()))
                                .query(BigDecimal.class)
                                .single())
                .isEqualByComparingTo("0.01");
    }

    @Test
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
        assertThat(bookings.get(userId, Long.parseLong(booking.id()), false).status())
                .isEqualTo("EXPIRED");
        assertThat(heldNights()).isZero();
        assertThat(
                        jdbc.sql("select reason from payment_exception where attempt_id=?")
                                .param(Long.parseLong(attempt.id()))
                                .query(String.class)
                                .single())
                .isEqualTo("LATE_PAYMENT");
    }
}
