package com.holidaybooking.backend.booking;

import static com.holidaybooking.backend.support.TestEvidence.*;
import static org.assertj.core.api.Assertions.*;

import com.holidaybooking.backend.booking.application.*;
import com.holidaybooking.backend.booking.domain.Stay;
import com.holidaybooking.backend.payment.application.RefundService;
import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.support.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 真实 MySQL 下验证取消、版本切换、补款到期及退款资金平衡；不访问真实支付渠道。 */
class BookingChangeTest extends BusinessTestSupport {
    @Autowired private BookingChangeService changes;
    @Autowired private RefundService refunds;

    @Test
    @CaseSpec(
            input = "客户为本人未付款订单创建取消报价；本人分页查看changes；另一个客户读取同订单changes",
            expected = "本人total=1且返回报价ID；其他客户拒绝，不能通过历史列表越权")
    void changeHistoryRemainsOwnedAndPaginated() {
        var f = fixture(false);
        var quote = changes.quoteCancellation(f.user(), f.booking());
        var history = changes.list(f.user(), f.booking(), 0, 20);
        assertThat(actual(history.total())).isEqualTo(1);
        assertThat(actual(history.items().getFirst().id())).isEqualTo(quote.id());
        assertThatThrownBy(expectFailure(() -> changes.list(user(), f.booking(), 0, 20)))
                .isInstanceOf(DomainException.class);
    }

    private record Fixture(long user, long type, long booking, String order) {}

    private Fixture fixture(boolean paid) {
        long user = user();
        long type = type(user, 1);
        var booking =
                book(user, Long.parseLong(quote(user, type).id()), UUID.randomUUID().toString());
        if (paid) pay(user, booking.paymentOrderId(), booking.totalAmount(), "Alipay");
        return new Fixture(user, type, Long.parseLong(booking.id()), booking.paymentOrderId());
    }

    private BookingChangeService.View amendment(Fixture f, LocalDate end) {
        return changes.quoteAmendment(
                f.user(),
                f.booking(),
                new Stay(stay().checkIn(), end),
                List.of(new BookingService.Selection(f.type(), 1, 2)));
    }

    private long nights(long booking, String state) {
        return jdbc.sql("select count(*) from room_night where booking_id=? and state=?")
                .params(booking, state)
                .query(Long.class)
                .single();
    }

    @Test
    @CaseSpec(
            input = "100.05/晚、两晚未付款预订；取消报价后以相同幂等键提交两次",
            expected = "同一变更APPLIED；预订CANCELLED；应付单EXPIRED；占房0；无退款")
    void unpaidCancellationIsIdempotentAndReleasesInventory() {
        var f = fixture(false);
        var quote = changes.quoteCancellation(f.user(), f.booking());
        String key = UUID.randomUUID().toString();
        var result =
                changes.submit(f.user(), f.booking(), Long.parseLong(quote.id()), "CANCEL", key);
        assertThat(
                        actual(
                                changes.submit(
                                                f.user(),
                                                f.booking(),
                                                Long.parseLong(quote.id()),
                                                "CANCEL",
                                                key)
                                        .id()))
                .isEqualTo(result.id());
        assertThat(actual(result.state())).isEqualTo("APPLIED");
        assertThat(actual(bookings.get(f.user(), f.booking(), false).status()))
                .isEqualTo("CANCELLED");
        assertThat(actual(payments.get(f.user(), Long.parseLong(f.order())).status()))
                .isEqualTo("EXPIRED");
        assertThat(actual(nights(f.booking(), "HELD"))).isZero();
        assertThat(actual(result.refunds())).isEmpty();
    }

    @Test
    @CaseSpec(
            input = "已支付200.10的两晚预订；取消后刷新同一退款两次",
            expected = "取消释放所有BOOKED；退款先PENDING再SUCCEEDED；金额200.10；mock_refund仅一条")
    void paidCancellationReservesAndRefundsExactlyOnce() {
        var f = fixture(true);
        var quote = changes.quoteCancellation(f.user(), f.booking());
        var result =
                changes.submit(
                        f.user(),
                        f.booking(),
                        Long.parseLong(quote.id()),
                        "CANCEL",
                        UUID.randomUUID().toString());
        assertThat(actual(nights(f.booking(), "BOOKED"))).isZero();
        var refund = result.refunds().getFirst();
        assertThat(actual(refund.amount())).isEqualByComparingTo("200.10");
        assertThat(actual(refund.state())).isEqualTo("PENDING");
        assertThat(actual(refunds.refresh(f.user(), refund.id()).state())).isEqualTo("SUCCEEDED");
        refunds.refresh(f.user(), refund.id());
        assertThat(
                        actual(
                                jdbc.sql("select count(*) from mock_refund where request_no=?")
                                        .param(refund.requestNo())
                                        .query(Long.class)
                                        .single()))
                .isEqualTo(1L);
    }

    @Test
    @CaseSpec(
            input = "已支付两晚200.10；同一房间改成一晚100.05；新入住仍11月10日",
            expected = "APPLIED；revision=2；仅1晚BOOKED；差价-100.05；退款PENDING；退款完成前禁止再次变更")
    void cheaperAmendmentReusesOwnRoomAndPreservesHistory() {
        var f = fixture(true);
        var quote = amendment(f, LocalDate.of(2026, 11, 11));
        var result =
                changes.submit(
                        f.user(),
                        f.booking(),
                        Long.parseLong(quote.id()),
                        "AMEND",
                        UUID.randomUUID().toString());
        assertThat(actual(result.priceDifference())).isEqualTo("-100.05");
        assertThat(actual(result.state())).isEqualTo("APPLIED");
        assertThat(actual(bookings.get(f.user(), f.booking(), false).revision())).isEqualTo(2);
        assertThat(actual(nights(f.booking(), "BOOKED"))).isEqualTo(1L);
        assertThat(
                        actual(
                                jdbc.sql("select count(*) from booking_revision where booking_id=?")
                                        .param(f.booking())
                                        .query(Long.class)
                                        .single()))
                .isEqualTo(2L);
        assertThatThrownBy(expectFailure(() -> changes.quoteCancellation(f.user(), f.booking())))
                .isInstanceOf(DomainException.class);
        assertThat(actual(refunds.refresh(f.user(), result.refunds().getFirst().id()).state()))
                .isEqualTo("SUCCEEDED");
        assertThat(actual(changes.quoteCancellation(f.user(), f.booking()).priceDifference()))
                .isEqualTo("-100.05");
    }

    @Test
    @CaseSpec(
            input = "两晚200.10改为三晚300.15；支付补款100.05后取消整单",
            expected = "补款前原revision=1且BOOKED=2、HELD=1；补款后revision=2、BOOKED=3；取消退款合计300.15分配到两笔支付")
    void supplementKeepsOriginalUntilPaidAndRefundsAcrossPayments() {
        var f = fixture(true);
        var quote = amendment(f, LocalDate.of(2026, 11, 13));
        var result =
                changes.submit(
                        f.user(),
                        f.booking(),
                        Long.parseLong(quote.id()),
                        "AMEND",
                        UUID.randomUUID().toString());
        assertThat(actual(result.state())).isEqualTo("AWAITING_PAYMENT");
        assertThat(actual(bookings.get(f.user(), f.booking(), false).revision())).isEqualTo(1);
        assertThat(actual(nights(f.booking(), "BOOKED"))).isEqualTo(2L);
        assertThat(actual(nights(f.booking(), "HELD"))).isEqualTo(1L);
        pay(f.user(), result.paymentOrderId(), "100.05", "Alipay");
        assertThat(actual(bookings.get(f.user(), f.booking(), false).revision())).isEqualTo(2);
        assertThat(actual(nights(f.booking(), "BOOKED"))).isEqualTo(3L);
        var cancellation = changes.quoteCancellation(f.user(), f.booking());
        var cancelled =
                changes.submit(
                        f.user(),
                        f.booking(),
                        Long.parseLong(cancellation.id()),
                        "CANCEL",
                        UUID.randomUUID().toString());
        assertThat(actual(cancelled.refunds().size())).isEqualTo(2);
        assertThat(
                        actual(
                                cancelled.refunds().stream()
                                        .map(r -> r.amount())
                                        .reduce(BigDecimal.ZERO, BigDecimal::add)))
                .isEqualByComparingTo("300.15");
        cancelled
                .refunds()
                .forEach(
                        r ->
                                assertThat(actual(refunds.refresh(f.user(), r.id()).state()))
                                        .isEqualTo("SUCCEEDED"));
    }

    @Test
    @CaseSpec(
            input = "两晚改三晚，未发起补款；时钟推进16分钟后刷新补款单",
            expected = "变更EXPIRED；原预订CONFIRMED且revision=1；保留2晚BOOKED，仅释放新增HELD")
    void expiredSupplementOnlyReleasesAdditionalNights() {
        var f = fixture(true);
        var quote = amendment(f, LocalDate.of(2026, 11, 13));
        var result =
                changes.submit(
                        f.user(),
                        f.booking(),
                        Long.parseLong(quote.id()),
                        "AMEND",
                        UUID.randomUUID().toString());
        clock.advance(Duration.ofMinutes(16));
        payments.refresh(f.user(), Long.parseLong(result.paymentOrderId()));
        assertThat(actual(changes.get(f.user(), f.booking(), Long.parseLong(result.id())).state()))
                .isEqualTo("EXPIRED");
        assertThat(actual(bookings.get(f.user(), f.booking(), false).status()))
                .isEqualTo("CONFIRMED");
        assertThat(actual(bookings.get(f.user(), f.booking(), false).revision())).isEqualTo(1);
        assertThat(actual(nights(f.booking(), "BOOKED"))).isEqualTo(2L);
        assertThat(actual(nights(f.booking(), "HELD"))).isZero();
    }

    @Test
    @CaseSpec(
            input = "他人尝试取消；本人在入住前24小时报价，再推进1秒提交",
            expected = "他人NOT_FOUND；24小时整允许报价；不足24小时NOTICE_PERIOD，原预订及库存不变")
    void ownershipAndTwentyFourHourBoundaryAreEnforced() {
        var f = fixture(true);
        long other = user();
        assertThatThrownBy(expectFailure(() -> changes.quoteCancellation(other, f.booking())))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("NOT_FOUND");
        clock.advance(Duration.between(clock.instant(), Instant.parse("2026-11-09T07:00:00Z")));
        var quote = changes.quoteCancellation(f.user(), f.booking());
        clock.advance(Duration.ofSeconds(1));
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        changes.submit(
                                                f.user(),
                                                f.booking(),
                                                Long.parseLong(quote.id()),
                                                "CANCEL",
                                                UUID.randomUUID().toString())))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("NOTICE_PERIOD");
        assertThat(actual(nights(f.booking(), "BOOKED"))).isEqualTo(2L);
    }
}
