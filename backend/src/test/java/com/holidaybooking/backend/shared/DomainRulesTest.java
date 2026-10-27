package com.holidaybooking.backend.shared;

import static com.holidaybooking.backend.support.TestEvidence.*;
import static org.assertj.core.api.Assertions.*;

import com.holidaybooking.backend.booking.domain.Stay;
import com.holidaybooking.backend.shared.domain.*;
import com.holidaybooking.backend.support.*;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
class DomainRulesTest {
    @Test
    @CaseSpec(input = "100.05乘0.9；非法金额1.001和-1", expected = "逐晚结果90.05；超精度及负数金额拒绝")
    void roundsEachNightBeforeSumming() {
        assertThat(actual(Money.of("100.05").discounted(new BigDecimal("0.9")).text()))
                .isEqualTo("90.05");
        assertThatThrownBy(expectFailure(() -> Money.of("1.001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(expectFailure(() -> Money.of("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @CaseSpec(
            input = "10月22日到24日；当地14:59:59和15:00尝试当日入住；零晚区间",
            expected = "占22/23两晚不含24；截止前接受，15:00和零晚区间拒绝")
    void checkoutIsExclusiveAndSameDayCutoffUsesResortZone() {
        var stay = new Stay(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 24));
        assertThat(actual(stay.dates()))
                .containsExactly(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 23));
        stay.validate(
                Clock.fixed(Instant.parse("2026-10-22T06:59:59Z"), ZoneOffset.UTC),
                ZoneId.of("Asia/Shanghai"),
                LocalTime.of(15, 0),
                30,
                365);
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        stay.validate(
                                                Clock.fixed(
                                                        Instant.parse("2026-10-22T07:00:00Z"),
                                                        ZoneOffset.UTC),
                                                ZoneId.of("Asia/Shanghai"),
                                                LocalTime.of(15, 0),
                                                30,
                                                365)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(expectFailure(() -> new Stay(stay.checkIn(), stay.checkIn())))
                .isInstanceOf(DomainException.class);
    }
}
