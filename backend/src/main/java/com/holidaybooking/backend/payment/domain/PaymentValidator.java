package com.holidaybooking.backend.payment.domain;

import com.holidaybooking.backend.shared.domain.Money;
import java.math.BigDecimal;

/**
 * Q6 contract: compare against the amount read from our order, never a second client-supplied
 * value.
 */
public final class PaymentValidator {
    private PaymentValidator() {}

    public static void validate(BigDecimal expected, String assertedAmount, String method) {
        if (!"Alipay".equals(method) && !"Wechat".equals(method)) {
            throw new UnsupportedPaymentMethodException();
        }
        BigDecimal actual;
        try {
            actual = Money.of(assertedAmount).amount();
        } catch (RuntimeException ex) {
            throw new PaymentAmountMismatchException();
        }
        if (expected.compareTo(actual) != 0) {
            throw new PaymentAmountMismatchException();
        }
    }

    public static class UnsupportedPaymentMethodException extends IllegalArgumentException {
        public UnsupportedPaymentMethodException() {
            super("支付方式只能是 Alipay 或 Wechat");
        }
    }

    public static class PaymentAmountMismatchException extends IllegalArgumentException {
        public PaymentAmountMismatchException() {
            super("支付金额与订单应付金额不一致");
        }
    }
}
