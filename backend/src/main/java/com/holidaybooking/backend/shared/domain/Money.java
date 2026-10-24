package com.holidaybooking.backend.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** CNY only. Reject fractional cents instead of silently rounding submitted amounts. */
public record Money(BigDecimal amount) {
    public Money {
        if (amount == null
                || amount.signum() < 0
                || amount.compareTo(new BigDecimal("9999999999.99")) > 0) {
            throw new IllegalArgumentException("金额超出有效范围");
        }
        try {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("金额最多包含两位小数");
        }
    }

    public static Money of(String value) {
        return new Money(new BigDecimal(value));
    }

    public Money discounted(BigDecimal ratio) {
        return new Money(amount.multiply(ratio).setScale(2, RoundingMode.HALF_UP));
    }

    public String text() {
        return amount.toPlainString();
    }
}
