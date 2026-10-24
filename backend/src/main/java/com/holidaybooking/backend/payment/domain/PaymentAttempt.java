package com.holidaybooking.backend.payment.domain;

import com.holidaybooking.backend.shared.domain.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

/** 一次渠道支付尝试。商户订单号保持唯一，已成功状态不得被待支付或关闭通知回退。 */
@Entity
@Table(name = "payment_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAttempt extends BaseEntity {
    public enum Status {
        CREATED,
        PENDING,
        UNKNOWN,
        SUCCEEDED,
        CLOSED,
        EXCEPTION
    }

    @Column(nullable = false)
    private Long paymentOrderId;

    @Column(nullable = false, unique = true, length = 64)
    private String outTradeNo;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(nullable = false, length = 20)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.CREATED;

    @Column(length = 128)
    private String providerTradeNo;

    @Column(length = 2048)
    private String checkoutUrl;

    private Instant paidAt;

    public PaymentAttempt(long orderId, String method, String provider) {
        paymentOrderId = orderId;
        this.method = method;
        this.provider = provider;
        outTradeNo = "HB" + UUID.randomUUID().toString().replace("-", "");
    }

    public boolean unresolved() {
        return status == Status.CREATED || status == Status.PENDING || status == Status.UNKNOWN;
    }

    public void pending(String url) {
        if (unresolved()) {
            status = Status.PENDING;
            if (url != null) {
                checkoutUrl = url;
            }
        }
    }

    public void unknown() {
        if (unresolved()) {
            status = Status.UNKNOWN;
        }
    }

    public void close() {
        if (unresolved()) {
            status = Status.CLOSED;
        }
    }

    public void paid(String trade, Instant time, boolean exception) {
        providerTradeNo = trade;
        paidAt = time;
        status = exception ? Status.EXCEPTION : Status.SUCCEEDED;
    }
}
