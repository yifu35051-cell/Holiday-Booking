package com.holidaybooking.backend.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * External provider I/O stays outside the business transaction. The local wallet simulator joins
 * its debit transaction; only verified gateway results enter the business state machine.
 */
public interface PaymentGateway {
    enum State {
        PENDING,
        PAID,
        CLOSED,
        UNKNOWN
    }

    record Command(
            String outTradeNo,
            String method,
            BigDecimal amount,
            Instant expiresAt,
            String subject) {}

    record Result(
            String outTradeNo,
            State state,
            BigDecimal amount,
            String tradeNo,
            Instant paidAt,
            String checkoutUrl) {}

    String provider();

    Result create(Command command);

    Result query(Command command);

    Result close(Command command);

    record RefundCommand(String requestNo, String outTradeNo, BigDecimal amount) {}

    enum RefundState {
        SUCCEEDED,
        UNKNOWN
    }

    /** 同一退款号可安全重试；不能把网络超时当成明确失败。 */
    default RefundState refund(RefundCommand command) {
        return RefundState.UNKNOWN;
    }

    default Result verifyCallback(Map<String, String> parameters) {
        throw new UnsupportedOperationException("Callbacks unavailable");
    }

    default void simulate(Command command) {
        throw new UnsupportedOperationException("Simulation unavailable");
    }
}
