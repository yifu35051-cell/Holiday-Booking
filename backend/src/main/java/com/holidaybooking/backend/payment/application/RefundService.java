package com.holidaybooking.backend.payment.application;

import com.holidaybooking.backend.identity.domain.IdentityRepository;
import com.holidaybooking.backend.payment.domain.*;
import com.holidaybooking.backend.shared.config.BookingProperties;
import com.holidaybooking.backend.shared.domain.*;
import java.time.Clock;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 退款网络调用在业务事务外执行；资金状态和预订状态分别记录。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {
    private final RefundRepository refunds;
    private final PaymentGateway gateway;
    private final IdentityRepository users;
    private final TransactionTemplate tx;
    private final BookingProperties settings;
    private final OperationStore operations;
    private final Clock clock;

    public RefundRepository.Refund get(long user, long id) {
        var refund = tx.execute(status -> refunds.get(id));
        if (Objects.requireNonNull(refund).userId() != user) throw DomainException.missing();
        return refund;
    }

    public RefundRepository.Refund refresh(long user, long id) {
        var refund = get(user, id);
        if (refund.state().equals("SUCCEEDED")) return refund;
        if (!refund.provider().equals(gateway.provider())) {
            throw DomainException.unavailable("退款所属渠道当前未启用");
        }
        PaymentGateway.RefundState result;
        try {
            result =
                    gateway.refund(
                            new PaymentGateway.RefundCommand(
                                    refund.requestNo(), refund.outTradeNo(), refund.amount()));
        } catch (RuntimeException failure) {
            result = PaymentGateway.RefundState.UNKNOWN;
        }
        final var outcome = result;
        tx.executeWithoutResult(
                status -> {
                    users.lock(user);
                    if (refunds.get(id).state().equals("SUCCEEDED")) return;
                    refunds.complete(
                            id,
                            outcome.name(),
                            outcome == PaymentGateway.RefundState.SUCCEEDED
                                    ? "Provider confirmed refund"
                                    : "Provider result unknown; retry uses the same request number");
                    operations.audit(
                            user,
                            "REFUND_" + outcome.name(),
                            "REFUND",
                            id,
                            refund.requestNo(),
                            clock.instant());
                });
        return get(user, id);
    }

    @Scheduled(fixedDelayString = "${booking.job-delay-ms:60000}")
    public void scheduled() {
        if (!settings.isJobsEnabled()) return;
        long cursor = 0;
        while (true) {
            long after = cursor;
            var ids = Objects.requireNonNull(tx.execute(status -> refunds.pending(after, 100)));
            for (long id : ids) {
                try {
                    var refund = Objects.requireNonNull(tx.execute(status -> refunds.get(id)));
                    refresh(refund.userId(), id);
                } catch (RuntimeException failure) {
                    log.warn("Refund recovery deferred refund={}", id);
                }
            }
            if (ids.size() < 100) break;
            cursor = ids.getLast();
        }
    }
}
