package com.holidaybooking.backend.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * All I/O is outside the business transaction. Only verified gateway results enter the state
 * machine.
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

    default Result verifyCallback(Map<String, String> parameters) {
        throw new UnsupportedOperationException("Callbacks unavailable");
    }

    default void simulate(Command command) {
        throw new UnsupportedOperationException("Simulation unavailable");
    }
}
