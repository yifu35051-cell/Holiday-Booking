package com.holidaybooking.backend.payment.domain;

import com.holidaybooking.backend.shared.domain.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.*;

/** 业务应付单，仅关联一笔预订或会员购买；应付金额以此处的服务端快照为准。 */
@Entity
@Table(name = "payment_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentOrder extends BaseEntity {
    public enum Status {
        PENDING,
        SUCCEEDED,
        EXPIRED
    }

    @Column(nullable = false)
    private Long userId;

    @Column(unique = true)
    private Long bookingId;

    @Column(unique = true)
    private Long membershipPurchaseId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "CNY";

    @Column(nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    private Long successfulAttemptId;

    public PaymentOrder(
            long user, Long booking, Long purchase, BigDecimal amount, Instant expires) {
        if ((booking == null) == (purchase == null)) {
            throw new IllegalArgumentException("Exactly one business reference is required");
        }
        userId = user;
        bookingId = booking;
        membershipPurchaseId = purchase;
        this.amount = amount;
        expiresAt = expires;
    }

    public void succeed(long attempt) {
        status = Status.SUCCEEDED;
        successfulAttemptId = attempt;
    }

    public void expire() {
        if (status == Status.PENDING) {
            status = Status.EXPIRED;
        }
    }
}
