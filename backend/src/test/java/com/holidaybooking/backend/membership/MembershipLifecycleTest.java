package com.holidaybooking.backend.membership;

import static com.holidaybooking.backend.support.TestEvidence.*;
import static org.assertj.core.api.Assertions.*;

import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.support.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

/** 会员激活、续期及到期测试；模拟付款，不调用真实支付服务。 */
@Tag("channels")
public class MembershipLifecycleTest extends BusinessTestSupport {
    @Test
    @CaseSpec(
            input = "购买默认199元会员并重复模拟回调，再按新方案发起续费",
            expected = "初始未激活；重复回调不延长；有效会员仍199元/0.9/365天；续期365天；两晚折后180.10")
    protected void paidMembershipActivatesOnceAndRenewalKeepsOriginalPlan() {
        long user = user();
        assertThat(actual(memberships.me(user).active())).isFalse();
        var purchase = memberships.purchase(user, 1, "member-key-0001");
        assertThat(actual(memberships.me(user).active())).isFalse();
        var paid = pay(user, purchase.paymentOrderId(), purchase.fee(), "Wechat");
        var until = memberships.me(user).validUntil();
        payments.simulate(user, Long.parseLong(paid.attempts().getFirst().id()));
        assertThat(actual(memberships.me(user).validUntil())).isEqualTo(until);
        var newer =
                memberships.createPlan(
                        user, "New plan", new BigDecimal("299"), new BigDecimal("0.8"), 100);
        var renewal = memberships.purchase(user, Long.parseLong(newer.id()), "member-key-0002");
        assertThat(actual(renewal.fee())).isEqualTo("199.00");
        assertThat(actual(renewal.discountRatio())).isEqualTo("0.9000");
        pay(user, renewal.paymentOrderId(), renewal.fee(), "Alipay");
        assertThat(actual(memberships.me(user).validUntil()))
                .isEqualTo(until.plus(Duration.ofDays(365)));
        assertThat(actual(quote(user, type(user, 1)).totalAmount())).isEqualTo("180.10");
    }

    @Test
    @CaseSpec(input = "已激活会员5分钟后到期，报价后推进6分钟", expected = "报价虽未过期，但会员权益变化导致下单被拒绝")
    protected void membershipExpiryInvalidatesAnOtherwiseUnexpiredQuote() {
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
                        expectFailure(
                                () ->
                                        book(
                                                customer,
                                                Long.parseLong(quote.id()),
                                                "expired-benefit-booking")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("会员权益已变化");
    }
}
