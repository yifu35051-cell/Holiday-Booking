package com.holidaybooking.backend;

import static org.assertj.core.api.Assertions.*;

import com.holidaybooking.backend.booking.application.BookingService;
import com.holidaybooking.backend.booking.domain.Stay;
import com.holidaybooking.backend.catalog.application.CatalogService;
import com.holidaybooking.backend.identity.application.IdentityService;
import com.holidaybooking.backend.membership.application.MembershipService;
import com.holidaybooking.backend.notification.application.NotificationService;
import com.holidaybooking.backend.payment.application.PaymentService;
import com.holidaybooking.backend.shared.domain.DomainException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class BusinessFlowTest {
    @Autowired IdentityService identity;
    @Autowired CatalogService catalog;
    @Autowired BookingService bookings;
    @Autowired MembershipService memberships;
    @Autowired PaymentService payments;
    @Autowired NotificationService notifications;
    @Autowired JdbcClient jdbc;
    @Autowired TestClockConfig.MutableClock clock;

    @BeforeEach
    void resetTime() {
        clock.reset();
    }

    long user() {
        return Long.parseLong(
                identity.register(
                                UUID.randomUUID() + "@example.com",
                                "test-password-123",
                                "Test Guest")
                        .id());
    }

    long type(long actor, int rooms) {
        long type =
                Long.parseLong(
                        catalog.createType(
                                        actor,
                                        "Garden " + UUID.randomUUID(),
                                        "Garden view",
                                        2,
                                        new BigDecimal("100.05"))
                                .id());
        for (int i = 0; i < rooms; i++) {
            catalog.createRoom(actor, type, UUID.randomUUID().toString().substring(0, 20));
        }
        return type;
    }

    Stay stay() {
        return new Stay(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12));
    }

    BookingService.QuoteView quote(long user, long type) {
        return bookings.quote(user, stay(), List.of(new BookingService.Selection(type, 1, 2)));
    }

    BookingService.BookingView book(long user, long quote, String key) {
        return bookings.create(user, quote, "Guest", "guest@example.com", "+86 13800000000", key);
    }

    PaymentService.OrderView pay(long user, String order, String amount, String method) {
        var started =
                payments.start(
                        user, Long.parseLong(order), amount, method, UUID.randomUUID().toString());
        return payments.simulate(user, Long.parseLong(started.attempts().getFirst().id()));
    }

    @Test
    void bookingSnapshotPaymentAndNotificationsFormOneConsistentFlow() {
        long user = user(), type = type(user, 1);
        var quote = quote(user, type);
        assertThat(quote.totalAmount()).isEqualTo("200.10");
        catalog.rates(user, type, stay(), new BigDecimal("999.00"));
        var booking = book(user, Long.parseLong(quote.id()), "booking-key-0001");
        assertThat(booking.totalAmount()).isEqualTo("200.10");
        assertThat(book(user, Long.parseLong(quote.id()), "booking-key-0001").id())
                .isEqualTo(booking.id());
        assertThatThrownBy(() -> book(user, Long.parseLong(quote.id()), "booking-key-0002"))
                .isInstanceOf(DomainException.class);
        assertThat(pay(user, booking.paymentOrderId(), booking.totalAmount(), "Alipay").status())
                .isEqualTo("SUCCEEDED");
        assertThat(bookings.get(user, Long.parseLong(booking.id()), false).status())
                .isEqualTo("CONFIRMED");
        payments.refresh(user, Long.parseLong(booking.paymentOrderId()));
        assertThat(
                        jdbc.sql("select count(*) from notification_task where booking_id=?")
                                .param(Long.parseLong(booking.id()))
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
        assertThat(
                        jdbc.sql(
                                        "select count(*) from room_night where booking_id=? and"
                                                + " state='BOOKED'")
                                .param(Long.parseLong(booking.id()))
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
        notifications.dispatch();
        assertThat(
                        jdbc.sql(
                                        "select state from notification_task where booking_id=? and"
                                                + " task_type='CONFIRMATION'")
                                .param(Long.parseLong(booking.id()))
                                .query(String.class)
                                .single())
                .isEqualTo("SIMULATED");
        assertThat(
                        jdbc.sql(
                                        "select due_at from notification_task where booking_id=?"
                                                + " and task_type='REMINDER_D1'")
                                .param(Long.parseLong(booking.id()))
                                .query(LocalDateTime.class)
                                .single())
                .isEqualTo(LocalDateTime.of(2026, 11, 9, 1, 0));
    }

    @Test
    void paidMembershipActivatesOnceAndRenewalKeepsOriginalPlan() {
        long user = user();
        assertThat(memberships.me(user).active()).isFalse();
        var purchase = memberships.purchase(user, 1, "member-key-0001");
        assertThat(memberships.me(user).active()).isFalse();
        var paid = pay(user, purchase.paymentOrderId(), purchase.fee(), "Wechat");
        var until = memberships.me(user).validUntil();
        payments.simulate(user, Long.parseLong(paid.attempts().getFirst().id()));
        assertThat(memberships.me(user).validUntil()).isEqualTo(until);
        var newer =
                memberships.createPlan(
                        user, "New plan", new BigDecimal("299"), new BigDecimal("0.8"), 100);
        var renewal = memberships.purchase(user, Long.parseLong(newer.id()), "member-key-0002");
        assertThat(renewal.fee()).isEqualTo("199.00");
        assertThat(renewal.discountRatio()).isEqualTo("0.9000");
        pay(user, renewal.paymentOrderId(), renewal.fee(), "Alipay");
        assertThat(memberships.me(user).validUntil()).isEqualTo(until.plus(Duration.ofDays(365)));
        assertThat(quote(user, type(user, 1)).totalAmount()).isEqualTo("180.10");
    }

    @Test
    void unpaidExpiryReleasesAllHeldNights() {
        long user = user(), type = type(user, 1);
        var q = quote(user, type);
        var b = book(user, Long.parseLong(q.id()), "expiry-booking-key");
        clock.advance(Duration.ofMinutes(16));
        payments.reconcile(Long.parseLong(b.paymentOrderId()));
        assertThat(bookings.get(user, Long.parseLong(b.id()), false).status()).isEqualTo("EXPIRED");
        assertThat(
                        jdbc.sql("select count(*) from room_night where booking_id=?")
                                .param(Long.parseLong(b.id()))
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Test
    void dailyFreeCountsDoNotImplyAContinuousAvailableRoom() {
        long user = user(), type = type(user, 2);
        var rooms = catalog.rooms(type);
        catalog.block(
                user,
                Long.parseLong(rooms.get(0).id()),
                new Stay(stay().checkIn(), stay().checkIn().plusDays(1)),
                "Maintenance");
        catalog.block(
                user,
                Long.parseLong(rooms.get(1).id()),
                new Stay(stay().checkIn().plusDays(1), stay().checkOut()),
                "Maintenance");
        assertThat(catalog.availability(stay(), 2, 1))
                .noneMatch(a -> a.roomType().id().equals(Long.toString(type)));
        assertThatThrownBy(() -> quote(user, type)).isInstanceOf(DomainException.class);
    }

    @Test
    void competingCustomersCannotBothBookTheLastRoom() throws Exception {
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
            assertThat(List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(
                        jdbc.sql(
                                        "select count(*) from room_night n join room r on"
                                                + " n.room_id=r.id where r.room_type_id=?")
                                .param(type)
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
    }

    @Test
    void multiTypeFailureRollsBackConsumptionAndAnyInventoryChanges() {
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
        assertThatThrownBy(() -> book(user, Long.parseLong(q.id()), "atomic-book-key"))
                .isInstanceOf(DomainException.class);
        assertThat(
                        jdbc.sql("select consumed_at from booking_quote where id=?")
                                .param(Long.parseLong(q.id()))
                                .query()
                                .singleRow()
                                .get("consumed_at"))
                .isNull();
        assertThat(catalog.availability(stay(), 1, 1))
                .anyMatch(v -> v.roomType().id().equals(Long.toString(a)));
    }

    @Test
    void expiredQuoteAndChangedIdempotencyPayloadAreRejected() {
        long user = user(), type = type(user, 2);
        var q = quote(user, type);
        clock.advance(Duration.ofMinutes(11));
        assertThatThrownBy(() -> book(user, Long.parseLong(q.id()), "expired-quote-key"))
                .isInstanceOf(DomainException.class);
        var fresh = quote(user, type);
        book(user, Long.parseLong(fresh.id()), "payload-check-key");
        assertThatThrownBy(
                        () ->
                                bookings.create(
                                        user,
                                        Long.parseLong(fresh.id()),
                                        "Other",
                                        "guest@example.com",
                                        "+86 13800000000",
                                        "payload-check-key"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void membershipExpiryInvalidatesAnOtherwiseUnexpiredQuote() {
        long customer = user();
        var purchase = memberships.purchase(customer, 1, "expiring-membership-key");
        pay(customer, purchase.paymentOrderId(), purchase.fee(), "Alipay");
        jdbc.sql("update membership set valid_until=? where user_id=?")
                .params(
                        com.holidaybooking.backend.shared.infrastructure.JdbcTime.write(
                                clock.instant().plusSeconds(300)),
                        customer)
                .update();
        var quote = quote(customer, type(customer, 1));
        clock.advance(Duration.ofMinutes(6));

        assertThatThrownBy(
                        () -> book(customer, Long.parseLong(quote.id()), "expired-benefit-booking"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("会员权益已变化");
    }

    @Test
    void ownershipAndOccupiedRoomProtectionApplyInServices() {
        long user = user(), other = user(), type = type(user, 1);
        var b = book(user, Long.parseLong(quote(user, type).id()), "protected-booking-key");
        assertThatThrownBy(() -> bookings.get(other, Long.parseLong(b.id()), false))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> payments.get(other, Long.parseLong(b.paymentOrderId())))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        () ->
                                catalog.activate(
                                        user,
                                        Long.parseLong(catalog.rooms(type).getFirst().id()),
                                        false))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void paymentRetryIsIdempotentAndDifferentAttemptIsBlocked() {
        long user = user(), type = type(user, 1);
        var b = book(user, Long.parseLong(quote(user, type).id()), "attempt-booking-key");
        long order = Long.parseLong(b.paymentOrderId());
        var p = payments.start(user, order, b.totalAmount(), "Alipay", "same-attempt-key");
        assertThat(
                        payments.start(user, order, b.totalAmount(), "Alipay", "same-attempt-key")
                                .attempts())
                .hasSize(1);
        assertThatThrownBy(
                        () ->
                                payments.start(
                                        user,
                                        order,
                                        b.totalAmount(),
                                        "Wechat",
                                        "another-attempt-key"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> payments.start(user, order, "0.01", "Alipay", "wrong-amount-key"))
                .isInstanceOf(IllegalArgumentException.class);
        payments.simulate(user, Long.parseLong(p.attempts().getFirst().id()));
    }
}
