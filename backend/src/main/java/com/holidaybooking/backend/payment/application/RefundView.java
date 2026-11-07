package com.holidaybooking.backend.payment.application;

import com.holidaybooking.backend.payment.domain.RefundRepository;

/** Public contract: decimal money and database identifiers are always strings. */
public record RefundView(
        String id,
        String userId,
        String changeId,
        String requestNo,
        String outTradeNo,
        String provider,
        String amount,
        String state,
        String detail) {
    public static RefundView of(RefundRepository.Refund refund) {
        return new RefundView(
                Long.toString(refund.id()),
                Long.toString(refund.userId()),
                Long.toString(refund.changeId()),
                refund.requestNo(),
                refund.outTradeNo(),
                refund.provider(),
                refund.amount().toPlainString(),
                refund.state(),
                refund.detail());
    }
}
