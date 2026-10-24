package com.holidaybooking.backend.payment.domain;

import java.time.Instant;
import java.util.*;

/** 支付订单、尝试与异常的持久化端口；异常记录与业务确认在同一事务内处理。 */
public interface PaymentRepository {
    PaymentOrder order(long id);

    PaymentOrder save(PaymentOrder order);

    PaymentOrder forBooking(long bookingId);

    PaymentOrder forPurchase(long purchaseId);

    PaymentAttempt attempt(long id);

    PaymentAttempt byOutTradeNo(String number);

    PaymentAttempt save(PaymentAttempt attempt);

    List<PaymentAttempt> attempts(long orderId);

    List<Long> pendingOrders(long afterId, int limit);

    void exception(
            long order, long attempt, String reason, PaymentGateway.Result received, Instant now);

    List<Map<String, Object>> exceptions(int offset, int size);

    void resolveException(long id, long actor, String note, Instant now);
}
