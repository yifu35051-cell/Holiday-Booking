package com.holidaybooking.backend.booking;

import static com.holidaybooking.backend.support.TestEvidence.*;
import static org.assertj.core.api.Assertions.*;

import com.holidaybooking.backend.booking.application.BookingService;
import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.support.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

/** 报价、预订及库存事务测试；公共数据准备由 BusinessTestSupport 提供。 */
@Tag("core")
public class BookingServiceTest extends BusinessTestSupport {
    @Test
    @CaseSpec(input = "两个用户持有同一房型报价，同时提交最后1间房的2晚预订", expected = "并发结果恰好一个true一个false；库存表只有2个夜记录")
    protected void competingCustomersCannotBothBookTheLastRoom() throws Exception {
        long first = user(), second = user(), type = type(first, 1);
        var q1 = quote(first, type);
        var q2 = quote(second, type);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> one =
                    () -> {
                        start.await();
                        try {
                            book(first, Long.parseLong(q1.id()), "concurrent-book-1");
                            return true;
                        } catch (DomainException ex) {
                            return false;
                        }
                    };
            Callable<Boolean> two =
                    () -> {
                        start.await();
                        try {
                            book(second, Long.parseLong(q2.id()), "concurrent-book-2");
                            return true;
                        } catch (DomainException ex) {
                            return false;
                        }
                    };
            var f1 = executor.submit(one);
            var f2 = executor.submit(two);
            start.countDown();
            assertThat(actual(List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS))))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(
                        actual(
                                jdbc.sql(
                                                "select count(*) from room_night n join room r on"
                                                        + " n.room_id=r.id where r.room_type_id=?")
                                        .param(type)
                                        .query(Long.class)
                                        .single()))
                .isEqualTo(2);
    }

    @Test
    @CaseSpec(input = "两个房型报价后封住第二房型，再创建预订", expected = "整笔失败；报价consumed_at为空；第一房型仍可用")
    protected void multiTypeFailureRollsBackConsumptionAndAnyInventoryChanges() {
        long user = user(), a = type(user, 1), b = type(user, 1);
        var q =
                bookings.quote(
                        user,
                        stay(),
                        List.of(
                                new BookingService.Selection(a, 1, 1),
                                new BookingService.Selection(b, 1, 1)));
        var room = catalog.rooms(b).getFirst();
        catalog.block(user, Long.parseLong(room.id()), stay(), "Maintenance");
        assertThatThrownBy(
                        expectFailure(() -> book(user, Long.parseLong(q.id()), "atomic-book-key")))
                .isInstanceOf(DomainException.class);
        assertThat(
                        actual(
                                jdbc.sql("select consumed_at from booking_quote where id=?")
                                        .param(Long.parseLong(q.id()))
                                        .query()
                                        .singleRow()
                                        .get("consumed_at")))
                .isNull();
        assertThat(actual(catalog.availability(stay(), 1, 1)))
                .anyMatch(v -> v.roomType().id().equals(Long.toString(a)));
    }

    @Test
    @CaseSpec(input = "报价后推进11分钟；新报价下单后同键更改联系人", expected = "分别拒绝过期报价和不同内容复用幂等键")
    protected void expiredQuoteAndChangedIdempotencyPayloadAreRejected() {
        long user = user(), type = type(user, 2);
        var q = quote(user, type);
        clock.advance(Duration.ofMinutes(11));
        assertThatThrownBy(
                        expectFailure(
                                () -> book(user, Long.parseLong(q.id()), "expired-quote-key")))
                .isInstanceOf(DomainException.class);
        var fresh = quote(user, type);
        book(user, Long.parseLong(fresh.id()), "payload-check-key");
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        bookings.create(
                                                user,
                                                Long.parseLong(fresh.id()),
                                                "Other",
                                                "guest@example.com",
                                                "+86 13800000000",
                                                "payload-check-key")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    @CaseSpec(input = "其他用户读取预订/应付单；尝试停用有未来占用的实体房", expected = "越权读取和停用操作均被拒绝")
    protected void ownershipAndOccupiedRoomProtectionApplyInServices() {
        long user = user(), other = user(), type = type(user, 1);
        var b = book(user, Long.parseLong(quote(user, type).id()), "protected-booking-key");
        assertThatThrownBy(expectFailure(() -> bookings.get(other, Long.parseLong(b.id()), false)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        expectFailure(
                                () -> payments.get(other, Long.parseLong(b.paymentOrderId()))))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        catalog.activate(
                                                user,
                                                Long.parseLong(catalog.rooms(type).getFirst().id()),
                                                false)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    @CaseSpec(
            input = "100.05/晚、住2晚的报价；报价后改当前房价999；相同幂等键重复下单",
            expected = "报价和订单仍200.10；PENDING_PAYMENT；同键同ID；列表按用户/状态/日期筛选；非法分页拒绝")
    protected void pendingBookingPreservesQuoteAndSupportsFilteredQueries() {
        long customer = user(), type = type(customer, 1);
        var quote = quote(customer, type);
        assertThat(
                        actual(
                                catalog.availability(stay(), 2, 1).stream()
                                        .filter(v -> v.roomType().id().equals(Long.toString(type)))
                                        .findFirst()
                                        .orElseThrow()
                                        .availableRooms()))
                .isEqualTo(1);
        catalog.rates(customer, type, stay(), new BigDecimal("999"));
        var booking = book(customer, Long.parseLong(quote.id()), "pending-snapshot-key");
        assertThat(actual(booking.totalAmount())).isEqualTo("200.10");
        assertThat(actual(booking.status())).isEqualTo("PENDING_PAYMENT");
        assertThat(actual(book(customer, Long.parseLong(quote.id()), "pending-snapshot-key").id()))
                .isEqualTo(booking.id());
        assertThat(
                        actual(
                                bookings.list(
                                                customer,
                                                com.holidaybooking.backend.booking.domain.Booking
                                                        .Status.PENDING_PAYMENT,
                                                stay().checkIn(),
                                                stay().checkOut(),
                                                0,
                                                10)
                                        .total()))
                .isEqualTo(1);
        assertThat(actual(bookings.list(user(), null, null, null, 0, 10).total())).isZero();
        assertThatThrownBy(expectFailure(() -> bookings.list(customer, null, null, null, 0, 101)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        bookings.list(
                                                customer,
                                                null,
                                                stay().checkOut(),
                                                stay().checkIn(),
                                                0,
                                                10)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    @CaseSpec(
            input = "重复房型选择、每间3人但容量2、请求11间以及他人消费本人的报价",
            expected = "非法选择/容量/数量返回业务异常；他人消费报价NOT_FOUND，报价仍可由本人成功下单")
    protected void quoteLimitsAndOwnershipAreEnforced() {
        long customer = user(), type = type(customer, 1);
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        bookings.quote(
                                                customer,
                                                stay(),
                                                List.of(
                                                        new BookingService.Selection(type, 1, 2),
                                                        new BookingService.Selection(type, 1, 2)))))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        bookings.quote(
                                                customer,
                                                stay(),
                                                List.of(new BookingService.Selection(type, 1, 3)))))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        bookings.quote(
                                                customer,
                                                stay(),
                                                List.of(
                                                        new BookingService.Selection(
                                                                type, 11, 1)))))
                .isInstanceOf(DomainException.class);
        var quote = quote(customer, type);
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        book(
                                                user(),
                                                Long.parseLong(quote.id()),
                                                "other-quote-owner")))
                .isInstanceOf(DomainException.class);
        assertThat(
                        actual(
                                book(customer, Long.parseLong(quote.id()), "correct-quote-owner")
                                        .status()))
                .isEqualTo("PENDING_PAYMENT");
    }
}
