package com.holidaybooking.backend.shared;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.holidaybooking.backend.booking.application.BookingService;
import com.holidaybooking.backend.booking.domain.*;
import com.holidaybooking.backend.catalog.application.CatalogService;
import com.holidaybooking.backend.catalog.domain.*;
import com.holidaybooking.backend.identity.domain.IdentityRepository;
import com.holidaybooking.backend.membership.application.MembershipService;
import com.holidaybooking.backend.notification.application.NotificationService;
import com.holidaybooking.backend.payment.application.PaymentLedger;
import com.holidaybooking.backend.payment.domain.PaymentRepository;
import com.holidaybooking.backend.shared.config.BookingProperties;
import com.holidaybooking.backend.shared.domain.*;
import com.holidaybooking.backend.support.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
class DesignBoundaryTest {
    @Test
    @CaseSpec(
            input = "Night price=0.01 CNY, membership ratio=0.01, one available room and one night",
            expected =
                    "Quote rejected with PRICE_TOO_LOW before any quote persistence; zero-value payment never created")
    void roundedZeroQuoteCannotCreatePayment() {
        var bookings = mock(BookingRepository.class);
        var catalog = mock(CatalogRepository.class);
        var membership = mock(MembershipService.class);
        var type = mock(RoomType.class);
        when(type.getId()).thenReturn(1L);
        when(type.isActive()).thenReturn(true);
        when(type.getCapacity()).thenReturn(2);
        when(type.getBasePrice()).thenReturn(new BigDecimal("0.01"));
        var arrival = LocalDate.of(2026, 11, 10);
        var departure = arrival.plusDays(1);
        when(catalog.type(1, true)).thenReturn(type);
        when(catalog.available(1, arrival, departure)).thenReturn(List.of(9L));
        when(catalog.rates(1, arrival, departure)).thenReturn(Map.of());
        when(membership.ratio(7)).thenReturn(new BigDecimal("0.01"));
        var service =
                new BookingService(
                        bookings,
                        catalog,
                        mock(CatalogService.class),
                        mock(IdentityRepository.class),
                        membership,
                        mock(PaymentLedger.class),
                        mock(PaymentRepository.class),
                        mock(NotificationService.class),
                        mock(OperationStore.class),
                        mock(SnapshotCodec.class),
                        new BookingProperties(),
                        Clock.systemUTC());
        assertThatThrownBy(
                        () ->
                                service.quote(
                                        7,
                                        new Stay(arrival, departure),
                                        List.of(new BookingService.Selection(1, 1, 1))))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        error ->
                                assertThat(TestEvidence.actual(error.getCode()))
                                        .isEqualTo("PRICE_TOO_LOW"));
        verifyNoInteractions(bookings);
    }

    @Test
    @CaseSpec(
            input = "All Java files under src/main/java in domain and application packages",
            expected =
                    "No application/domain reference to a project api or infrastructure package; Spring/JPA domain annotations allowed")
    void coreLayersDoNotDependOnTransportOrPersistenceAdapters() throws Exception {
        var violations = new ArrayList<String>();
        var forbidden =
                java.util.regex.Pattern.compile(
                        "com\\.holidaybooking\\.backend\\.[\\w.]+\\.(api|infrastructure)\\.");
        try (var sources = Files.walk(Path.of("src/main/java"))) {
            for (var path : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = path.toString().replace('\\', '/');
                if ((name.contains("/domain/") || name.contains("/application/"))
                        && forbidden.matcher(Files.readString(path)).find()) violations.add(name);
            }
        }
        assertThat(TestEvidence.actual(violations)).isEmpty();
    }
}
