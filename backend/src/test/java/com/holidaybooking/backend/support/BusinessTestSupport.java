package com.holidaybooking.backend.support;

import static org.assertj.core.api.Assertions.*;

import com.holidaybooking.backend.booking.application.BookingService;
import com.holidaybooking.backend.booking.domain.Stay;
import com.holidaybooking.backend.catalog.application.CatalogService;
import com.holidaybooking.backend.identity.application.IdentityService;
import com.holidaybooking.backend.membership.application.MembershipService;
import com.holidaybooking.backend.notification.application.NotificationService;
import com.holidaybooking.backend.payment.application.PaymentService;
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
public abstract class BusinessTestSupport extends MySqlTestSupport {
    @Autowired protected IdentityService identity;
    @Autowired protected CatalogService catalog;
    @Autowired protected BookingService bookings;
    @Autowired protected MembershipService memberships;
    @Autowired protected PaymentService payments;
    @Autowired protected NotificationService notifications;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected TestClockConfig.MutableClock clock;

    @BeforeEach
    protected void resetTime() {
        clock.reset();
    }

    protected long user() {
        return Long.parseLong(
                identity.register(
                                UUID.randomUUID() + "@example.com",
                                "test-password-123",
                                "Test Guest")
                        .id());
    }

    protected long type(long actor, int rooms) {
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

    protected Stay stay() {
        return new Stay(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12));
    }

    protected BookingService.QuoteView quote(long user, long type) {
        return bookings.quote(user, stay(), List.of(new BookingService.Selection(type, 1, 2)));
    }

    protected BookingService.BookingView book(long user, long quote, String key) {
        return bookings.create(user, quote, "Guest", "guest@example.com", "+86 13800000000", key);
    }

    protected PaymentService.OrderView pay(long user, String order, String amount, String method) {
        var started =
                payments.start(
                        user, Long.parseLong(order), amount, method, UUID.randomUUID().toString());
        return payments.simulate(user, Long.parseLong(started.attempts().getFirst().id()));
    }
}
