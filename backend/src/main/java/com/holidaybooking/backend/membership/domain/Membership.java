package com.holidaybooking.backend.membership.domain;

import com.holidaybooking.backend.shared.domain.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import lombok.*;

/** 用户的有效会员权益。续期从当前到期日或当前时间中较晚的一项开始计算。 */
@Entity
@Table(name = "membership")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Membership extends BaseEntity {
    @Column(nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false)
    private Long planId;

    @Column(nullable = false)
    private Instant validUntil;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal discountRatio;

    public Membership(long userId) {
        this.userId = userId;
        this.validUntil = Instant.EPOCH;
    }

    public boolean active(Instant now) {
        return validUntil.isAfter(now);
    }

    public void activate(MembershipPurchase purchase, Instant now) {
        // The user's row is locked by the payment workflow, so concurrent renewals cannot lose
        // days.
        validUntil =
                (active(now) ? validUntil : now).plus(Duration.ofDays(purchase.getDurationDays()));
        planId = purchase.getPlanId();
        discountRatio = purchase.getDiscountRatio();
    }
}
