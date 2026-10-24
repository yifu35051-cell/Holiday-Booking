package com.holidaybooking.backend.membership.domain;

import com.holidaybooking.backend.shared.domain.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.*;

/** 待支付会员购买单，保存购买时的价格、期限与折扣快照。 */
@Entity
@Table(name = "membership_purchase")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MembershipPurchase extends BaseEntity {
    public enum Status {
        PENDING_PAYMENT,
        ACTIVE,
        EXPIRED
    }

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long planId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal fee;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal discountRatio;

    @Column(nullable = false)
    private int durationDays;

    @Column(nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status = Status.PENDING_PAYMENT;

    public MembershipPurchase(long user, MembershipPlan plan, Instant expires) {
        userId = user;
        planId = plan.getId();
        fee = plan.getFee();
        discountRatio = plan.getDiscountRatio();
        durationDays = plan.getDurationDays();
        expiresAt = expires;
    }

    public void activate() {
        if (status != Status.PENDING_PAYMENT) {
            throw DomainException.conflict("PURCHASE_STATE", "会员订单不可支付");
        }
        status = Status.ACTIVE;
    }

    public void expire() {
        if (status == Status.PENDING_PAYMENT) {
            status = Status.EXPIRED;
        }
    }
}
