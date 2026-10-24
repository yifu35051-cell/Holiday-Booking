package com.holidaybooking.backend.catalog.domain;

import com.holidaybooking.backend.shared.domain.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.*;

/** 房型及默认每晚房价。调价只影响新报价，不修改历史预订快照。 */
@Entity
@Table(name = "room_type")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomType extends BaseEntity {
    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal basePrice;

    @Column(nullable = false)
    private boolean active = true;

    public RoomType(String name, String description, int capacity, BigDecimal price) {
        revise(name, description, capacity, price);
    }

    public void revise(String name, String description, int capacity, BigDecimal price) {
        if (capacity < 1 || capacity > 20) {
            throw DomainException.invalid("CAPACITY", "容量必须在 1–20 之间");
        }
        this.name = name.strip();
        this.description = description;
        this.capacity = capacity;
        this.basePrice = new Money(price).amount();
        if (this.basePrice.signum() == 0) {
            throw DomainException.invalid("PRICE", "价格必须大于零");
        }
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
