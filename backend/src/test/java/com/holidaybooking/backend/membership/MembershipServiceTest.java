package com.holidaybooking.backend.membership;

import static com.holidaybooking.backend.support.TestEvidence.*;
import static org.assertj.core.api.Assertions.*;

import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.support.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;

/** 不调用支付的会员方案、购买快照及归属规则。 */
@Tag("core")
class MembershipServiceTest extends BusinessTestSupport {
    @Test
    @CaseSpec(
            input = "客户购买99元/30天/0.85方案；本人和另一个客户分页查询purchases",
            expected = "本人total=1，返回同购买单和支付单；另一客户total=0，不泄漏其他账户记录")
    void purchaseHistoryIsScopedToTheCurrentUser() {
        long owner = user();
        var plan =
                memberships.createPlan(
                        owner, "History plan", new BigDecimal("99"), new BigDecimal("0.85"), 30);
        var purchase = memberships.purchase(owner, Long.parseLong(plan.id()), "history-key");
        var history = memberships.purchases(owner, 0, 20);
        assertThat(actual(history.total())).isEqualTo(1);
        assertThat(actual(history.items().getFirst().id())).isEqualTo(purchase.id());
        assertThat(actual(history.items().getFirst().paymentOrderId()))
                .isEqualTo(purchase.paymentOrderId());
        assertThat(actual(memberships.purchases(user(), 0, 20).total())).isZero();
    }

    @Test
    @CaseSpec(
            input = "99元、0.85折扣系数、30天方案；新用户重复提交同一购买键",
            expected = "购买单 PENDING_PAYMENT，保留99.00/0.8500/30快照；相同键返回同ID；会员仍未激活")
    void unpaidPurchaseIsIdempotentAndDoesNotGrantBenefits() {
        long customer = user();
        var plan =
                memberships.createPlan(
                        customer, "Test plan", new BigDecimal("99"), new BigDecimal("0.85"), 30);
        var purchase =
                memberships.purchase(customer, Long.parseLong(plan.id()), "member-snapshot-key");
        assertThat(actual(purchase.status())).isEqualTo("PENDING_PAYMENT");
        assertThat(actual(purchase.fee())).isEqualTo("99.00");
        assertThat(actual(purchase.discountRatio())).isEqualTo("0.8500");
        assertThat(actual(purchase.durationDays())).isEqualTo(30);
        assertThat(
                        actual(
                                memberships
                                        .purchase(
                                                customer,
                                                Long.parseLong(plan.id()),
                                                "member-snapshot-key")
                                        .id()))
                .isEqualTo(purchase.id());
        assertThat(actual(memberships.me(customer).active())).isFalse();
        assertThat(actual(memberships.me(customer).discountRatio())).isEqualTo("1.0000");
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        memberships.purchase(
                                                customer, 999999, "member-snapshot-key")))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        expectFailure(
                                () -> memberships.purchase(user(), Long.parseLong(purchase.id()))))
                .isInstanceOf(DomainException.class);
    }

    @Test
    @CaseSpec(input = "先创建和购买独立方案，再停用方案，以新键重新购买", expected = "公开方案列表隐藏停用项；新购买被拒绝；原购买单仍保留费用快照")
    void retiringPlanPreventsNewPurchasesButPreservesSnapshots() {
        long customer = user();
        var plan =
                memberships.createPlan(
                        customer, "Retire plan", new BigDecimal("88"), new BigDecimal("0.8"), 30);
        var purchase =
                memberships.purchase(customer, Long.parseLong(plan.id()), "before-retire-key");
        memberships.retirePlan(customer, Long.parseLong(plan.id()));
        assertThat(actual(memberships.plans().stream().anyMatch(p -> p.id().equals(plan.id()))))
                .isFalse();
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        memberships.purchase(
                                                customer,
                                                Long.parseLong(plan.id()),
                                                "after-retire-key")))
                .isInstanceOf(DomainException.class);
        assertThat(actual(memberships.purchase(customer, Long.parseLong(purchase.id())).fee()))
                .isEqualTo("88.00");
    }
}
