package com.holidaybooking.backend.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 退款预留金额包含处理中记录，防止未知渠道结果造成重复退款。 */
public interface RefundRepository {
    record Refund(
            long id,
            long userId,
            long changeId,
            String requestNo,
            String outTradeNo,
            String provider,
            BigDecimal amount,
            String state,
            String detail) {}

    boolean unsettled(long booking);

    void reserve(long change, long booking, BigDecimal amount, Instant now);

    List<Refund> forChange(long change);

    Refund get(long id);

    List<Long> pending(long after, int limit);

    void complete(long id, String state, String detail);
}
