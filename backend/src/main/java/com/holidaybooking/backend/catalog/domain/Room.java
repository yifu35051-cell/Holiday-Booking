package com.holidaybooking.backend.catalog.domain;

import com.holidaybooking.backend.shared.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/** 可实际分配给顾客的实体房间。停用采用逻辑状态，保留历史订单关联。 */
@Entity
@Table(name = "room")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Room extends BaseEntity {
    @Column(nullable = false)
    private Long roomTypeId;

    @Column(nullable = false, unique = true, length = 30)
    private String number;

    @Column(nullable = false)
    private boolean active = true;

    public Room(long typeId, String number) {
        this.roomTypeId = typeId;
        this.number = number.strip();
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
