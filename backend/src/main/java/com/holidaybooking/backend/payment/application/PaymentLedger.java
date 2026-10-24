package com.holidaybooking.backend.payment.application;

import com.holidaybooking.backend.payment.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Creates the payable in the same transaction as its booking or membership purchase. */
@Service
@RequiredArgsConstructor
public class PaymentLedger {
    private final PaymentRepository payments;

    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentOrder create(
            long user, Long booking, Long purchase, BigDecimal amount, Instant expires) {
        return payments.save(new PaymentOrder(user, booking, purchase, amount, expires));
    }
}
