package com.holidaybooking.backend.membership.domain;

import com.holidaybooking.backend.shared.domain.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.*;

/** 会员方案的独立版本。新增方案与停用旧方案不会改写已购买的权益。 */
@Entity
@Table(name = "membership_plan")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MembershipPlan extends BaseEntity {
    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal fee;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal discountRatio;

    @Column(nullable = false)
    private int durationDays;

    @Column(nullable = false)
    private boolean active = true;

    public MembershipPlan(String name, BigDecimal fee, BigDecimal ratio, int days) {
        if (ratio == null
                || ratio.signum() <= 0
                || ratio.compareTo(BigDecimal.ONE) >= 0
                || ratio.scale() > 4
                || days < 1
                || days > 3650) {
            throw DomainException.invalid("PLAN", "会员折扣或期限无效");
        }
        this.name = name.strip();
        this.fee = new Money(fee).amount();
        if (this.fee.signum() == 0) {
            throw DomainException.invalid("PLAN", "会员费必须大于零");
        }
        this.discountRatio = ratio.setScale(4);
        this.durationDays = days;
    }

    public void retire() {
        active = false;
    }
}
