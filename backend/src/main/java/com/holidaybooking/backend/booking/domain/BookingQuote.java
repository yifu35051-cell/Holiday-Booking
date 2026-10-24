package com.holidaybooking.backend.booking.domain;

import com.holidaybooking.backend.shared.domain.*;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/** 有时限且只能消费一次的服务端报价；报价本身不占用房间。 */
@Entity
@Table(name = "booking_quote")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BookingQuote extends BaseEntity {
    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String snapshot;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant consumedAt;

    public BookingQuote(long userId, String snapshot, Instant expiresAt) {
        this.userId = userId;
        this.snapshot = snapshot;
        this.expiresAt = expiresAt;
    }

    public void consume(Instant now) {
        if (consumedAt != null) {
            throw DomainException.conflict("QUOTE_CONSUMED", "报价已被使用");
        }
        if (!expiresAt.isAfter(now)) {
            throw DomainException.conflict("QUOTE_EXPIRED", "报价已过期，请重新报价");
        }
        consumedAt = now;
    }
}
