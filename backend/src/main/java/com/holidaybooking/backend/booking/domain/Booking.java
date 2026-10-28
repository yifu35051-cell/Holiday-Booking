package com.holidaybooking.backend.booking.domain;

import com.holidaybooking.backend.shared.domain.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import lombok.*;

/** 预订主记录。价格明细保存在不可变版本中，支付确认后才成为有效预订。 */
@Entity
@Table(name = "booking")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Booking extends BaseEntity {
    public enum Status {
        PENDING_PAYMENT,
        CONFIRMED,
        EXPIRED,
        CANCELLED
    }

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true)
    private Long quoteId;

    @Column(nullable = false)
    private LocalDate checkIn;

    @Column(nullable = false)
    private LocalDate checkOut;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 80)
    private String contactName;

    @Column(nullable = false, length = 254)
    private String contactEmail;

    @Column(nullable = false, length = 30)
    private String contactPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status = Status.PENDING_PAYMENT;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant confirmedAt;

    @Column(nullable = false)
    private int currentRevision = 1;

    public Booking(
            long user,
            long quote,
            QuoteSnapshot snapshot,
            String name,
            String email,
            String phone,
            Instant expires) {
        userId = user;
        quoteId = quote;
        checkIn = snapshot.checkIn();
        checkOut = snapshot.checkOut();
        totalAmount = snapshot.total();
        contactName = name.strip();
        contactEmail = email.strip();
        contactPhone = phone.strip();
        expiresAt = expires;
    }

    public void confirm(Instant paidAt) {
        if (status != Status.PENDING_PAYMENT) {
            throw DomainException.conflict("BOOKING_STATE", "当前预订不能确认");
        }
        status = Status.CONFIRMED;
        confirmedAt = paidAt;
    }

    public void expire() {
        if (status == Status.PENDING_PAYMENT) {
            status = Status.EXPIRED;
        }
    }

    public void cancel() {
        if (status != Status.CONFIRMED && status != Status.PENDING_PAYMENT) {
            throw DomainException.conflict("BOOKING_STATE", "当前预订不能取消");
        }
        status = Status.CANCELLED;
    }

    public void amend(QuoteSnapshot snapshot) {
        if (status != Status.CONFIRMED) {
            throw DomainException.conflict("BOOKING_STATE", "仅已确认预订可以改订");
        }
        currentRevision++;
        checkIn = snapshot.checkIn();
        checkOut = snapshot.checkOut();
        totalAmount = snapshot.total();
    }
}
