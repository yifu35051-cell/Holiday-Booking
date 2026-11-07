package com.holidaybooking.backend.payment.domain;

import com.holidaybooking.backend.shared.domain.DomainException;

/** Stable, sanitized classification; provider response bodies and credentials are never exposed. */
public class PaymentChannelException extends DomainException {
    public enum Reason {
        REJECTED,
        CONFIGURATION,
        UNAVAILABLE,
        INVALID_RESPONSE
    }

    private final Reason reason;

    public PaymentChannelException(Reason reason, String message) {
        super(
                reason == Reason.REJECTED ? Kind.CONFLICT : Kind.UNAVAILABLE,
                "CHANNEL_" + reason.name(),
                message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
