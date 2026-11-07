package com.holidaybooking.backend.payment.domain;

/** Persistent local channel. These operations join the wallet transaction and perform no I/O. */
public interface SandboxChannel {
    PaymentGateway.Result query(PaymentGateway.Command command);

    void simulate(PaymentGateway.Command command);
}
