package com.holidaybooking.backend.payment;

import static com.holidaybooking.backend.support.TestEvidence.*;
import static org.assertj.core.api.Assertions.*;

import com.holidaybooking.backend.payment.domain.PaymentValidator;
import com.holidaybooking.backend.shared.domain.*;
import com.holidaybooking.backend.support.*;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("channels")
class PaymentRulesTest {
    @Test
    @CaseSpec(
            input = "合法Alipay/Wechat及非法大小写、cash、空方式；不匹配或非法金额",
            expected =
                    "合法请求接受；非法方式UnsupportedPaymentMethodException；非法金额PaymentAmountMismatchException")
    void paymentMustMatchTrustedAmountAndExactMethod() {
        PaymentValidator.validate(new BigDecimal("123.00"), "123", "Alipay");
        PaymentValidator.validate(new BigDecimal("123.00"), "123.00", "Wechat");
        for (String method : new String[] {"alipay", "WeChat", "cash", "", null}) {
            assertThatThrownBy(
                            expectFailure(
                                    () ->
                                            PaymentValidator.validate(
                                                    BigDecimal.ONE, "1.00", method)))
                    .isInstanceOf(PaymentValidator.UnsupportedPaymentMethodException.class);
        }
        for (String amount : new String[] {"0.01", "NaN", "1.001", null}) {
            assertThatThrownBy(
                            expectFailure(
                                    () ->
                                            PaymentValidator.validate(
                                                    BigDecimal.ONE, amount, "Alipay")))
                    .isInstanceOf(PaymentValidator.PaymentAmountMismatchException.class);
        }
    }
}
