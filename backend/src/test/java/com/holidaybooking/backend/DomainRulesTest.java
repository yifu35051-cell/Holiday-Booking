package com.holidaybooking.backend;

import static org.assertj.core.api.Assertions.*;

import com.holidaybooking.backend.booking.domain.Stay;
import com.holidaybooking.backend.payment.domain.PaymentValidator;
import com.holidaybooking.backend.shared.domain.*;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;

class DomainRulesTest {
    @Test
    void roundsEachNightBeforeSumming() {
        assertThat(Money.of("100.05").discounted(new BigDecimal("0.9")).text()).isEqualTo("90.05");
        assertThatThrownBy(() -> Money.of("1.001")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("-1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void paymentMustMatchTrustedAmountAndExactMethod() {
        PaymentValidator.validate(new BigDecimal("123.00"), "123", "Alipay");
        PaymentValidator.validate(new BigDecimal("123.00"), "123.00", "Wechat");
        for (String method : new String[] {"alipay", "WeChat", "cash", "", null}) {
            assertThatThrownBy(() -> PaymentValidator.validate(BigDecimal.ONE, "1.00", method))
                    .isInstanceOf(PaymentValidator.UnsupportedPaymentMethodException.class);
        }
        for (String amount : new String[] {"0.01", "NaN", "1.001", null}) {
            assertThatThrownBy(() -> PaymentValidator.validate(BigDecimal.ONE, amount, "Alipay"))
                    .isInstanceOf(PaymentValidator.PaymentAmountMismatchException.class);
        }
    }

    @Test
    void checkoutIsExclusiveAndSameDayCutoffUsesResortZone() {
        var stay = new Stay(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 24));
        assertThat(stay.dates())
                .containsExactly(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 23));
        stay.validate(
                Clock.fixed(Instant.parse("2026-10-22T06:59:59Z"), ZoneOffset.UTC),
                ZoneId.of("Asia/Shanghai"),
                LocalTime.of(15, 0),
                30,
                365);
        assertThatThrownBy(
                        () ->
                                stay.validate(
                                        Clock.fixed(
                                                Instant.parse("2026-10-22T07:00:00Z"),
                                                ZoneOffset.UTC),
                                        ZoneId.of("Asia/Shanghai"),
                                        LocalTime.of(15, 0),
                                        30,
                                        365))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new Stay(stay.checkIn(), stay.checkIn()))
                .isInstanceOf(DomainException.class);
    }
}
