package com.holidaybooking.backend.payment.application;

import com.holidaybooking.backend.booking.application.BookingService;
import com.holidaybooking.backend.identity.domain.IdentityRepository;
import com.holidaybooking.backend.membership.application.MembershipService;
import com.holidaybooking.backend.payment.domain.*;
import com.holidaybooking.backend.shared.application.*;
import com.holidaybooking.backend.shared.config.BookingProperties;
import com.holidaybooking.backend.shared.domain.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 支付流程编排器。数据库事务内准备与落账，事务外调用渠道，避免网络调用长期占用数据库锁。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {
    private final PaymentRepository payments;
    private final IdentityRepository users;
    private final BookingService bookings;
    private final MembershipService memberships;
    private final PaymentGateway gateway;
    private final OperationStore operations;
    private final TransactionTemplate tx;
    private final BookingProperties settings;
    private final Clock clock;

    public record AttemptView(
            String id,
            String outTradeNo,
            String method,
            String provider,
            String status,
            String checkoutUrl,
            Instant paidAt) {}

    public record OrderView(
            String id,
            String status,
            String amount,
            String currency,
            Instant expiresAt,
            List<AttemptView> attempts) {}

    private record Work(
            long user,
            long order,
            long attempt,
            String provider,
            PaymentAttempt.Status status,
            PaymentGateway.Command command) {}

    private Work work(PaymentOrder order, PaymentAttempt attempt) {
        return new Work(
                order.getUserId(),
                order.getId(),
                attempt.getId(),
                attempt.getProvider(),
                attempt.getStatus(),
                new PaymentGateway.Command(
                        attempt.getOutTradeNo(),
                        attempt.getMethod(),
                        order.getAmount(),
                        order.getExpiresAt(),
                        order.getBookingId() != null
                                ? "Holiday Booking reservation"
                                : "Holiday Booking membership"));
    }

    public OrderView get(long user, long id) {
        return tx.execute(
                transactionStatus -> {
                    var order = payments.order(id);
                    owner(order, user);
                    return view(order);
                });
    }

    private OrderView view(PaymentOrder order) {
        return new OrderView(
                order.getId().toString(),
                order.getStatus().name(),
                order.getAmount().toPlainString(),
                order.getCurrency(),
                order.getExpiresAt(),
                payments.attempts(order.getId()).stream()
                        .map(
                                attempt ->
                                        new AttemptView(
                                                attempt.getId().toString(),
                                                attempt.getOutTradeNo(),
                                                attempt.getMethod(),
                                                attempt.getProvider(),
                                                attempt.getStatus().name(),
                                                attempt.getCheckoutUrl(),
                                                attempt.getPaidAt()))
                        .toList());
    }

    /** 三个阶段：事务内保存支付尝试 → 事务外请求渠道 → 新事务内校验并应用结果。 重试复用同一个商户订单号，不能因网络超时创建另一笔待支付交易。 */
    public OrderView start(long user, long id, String amount, String method, String key) {
        String hash = RequestFingerprint.hash(key, id + "\n" + amount + "\n" + method);
        Work work =
                tx.execute(
                        transactionStatus -> {
                            users.lock(user);
                            var order = payments.order(id);
                            owner(order, user);
                            PaymentValidator.validate(order.getAmount(), amount, method);
                            Long prior = operations.replay(user, "START_PAYMENT", key, hash);
                            if (prior != null) {
                                var attempt = payments.attempt(prior);
                                return work(order, attempt);
                            }
                            if (order.getStatus() != PaymentOrder.Status.PENDING
                                    || !order.getExpiresAt().isAfter(clock.instant())) {
                                throw DomainException.conflict("ORDER_NOT_PAYABLE", "订单已完成或已到期");
                            }
                            if (gateway.provider().equals("DISABLED")
                                    || (gateway.provider().equals("ALIPAY_SANDBOX")
                                            && !"Alipay".equals(method))) {
                                throw DomainException.unavailable(
                                        "所选支付渠道未配置；本地 mock 支持 Alipay 和 Wechat");
                            }
                            if (payments.attempts(id).stream()
                                    .anyMatch(PaymentAttempt::unresolved)) {
                                throw DomainException.conflict(
                                        "PAYMENT_IN_PROGRESS", "已有待确认支付，请先查询其结果");
                            }
                            var attempt =
                                    payments.save(
                                            new PaymentAttempt(id, method, gateway.provider()));
                            operations.remember(
                                    user,
                                    "START_PAYMENT",
                                    key,
                                    hash,
                                    attempt.getId(),
                                    clock.instant());
                            return work(order, attempt);
                        });
        Objects.requireNonNull(work);
        requireProvider(work);
        PaymentGateway.Result result;
        try {
            result =
                    work.status() == PaymentAttempt.Status.CREATED
                            ? gateway.create(work.command())
                            : gateway.query(work.command());
        } catch (RuntimeException ex) {
            log.warn("Payment provider request failed attempt={}", work.attempt());
            result = unknown(work);
        }
        apply(work, result);
        return get(user, id);
    }

    public OrderView refresh(long user, long id) {
        get(user, id);
        reconcile(id);
        return get(user, id);
    }

    public OrderView simulate(long user, long attemptId) {
        if (!gateway.provider().equals("MOCK")) {
            throw DomainException.missing();
        }
        Work work =
                tx.execute(
                        transactionStatus -> {
                            var attempt = payments.attempt(attemptId);
                            var order = payments.order(attempt.getPaymentOrderId());
                            owner(order, user);
                            return work(order, attempt);
                        });
        gateway.simulate(Objects.requireNonNull(work).command());
        apply(work, gateway.query(work.command()));
        return get(user, work.order());
    }

    /** 此入口只接受适配器验签后的结果；浏览器返回页不能用于确认支付。 */
    public void callback(Map<String, String> parameters) {
        if (!gateway.provider().equals("ALIPAY_SANDBOX")) {
            throw DomainException.missing();
        }
        var result = gateway.verifyCallback(parameters);
        Work work =
                tx.execute(
                        transactionStatus -> {
                            var attempt = payments.byOutTradeNo(result.outTradeNo());
                            return work(payments.order(attempt.getPaymentOrderId()), attempt);
                        });
        requireProvider(Objects.requireNonNull(work));
        apply(work, result);
    }

    /** 回调、主动查询与本地模拟都经过这里，保证相同事件只推进一次业务状态。 */
    private void apply(Work work, PaymentGateway.Result result) {
        if (!work.command().outTradeNo().equals(result.outTradeNo())) {
            throw DomainException.invalid("TRADE_NUMBER", "渠道订单号不匹配");
        }
        tx.executeWithoutResult(
                transactionStatus -> {
                    users.lock(work.user());
                    var order = payments.order(work.order());
                    var attempt = payments.attempt(work.attempt());
                    if (result.state() == PaymentGateway.State.PAID) {
                        // Repeated callbacks cannot activate a membership or confirm inventory
                        // twice.
                        if (attempt.getStatus() == PaymentAttempt.Status.SUCCEEDED
                                || attempt.getStatus() == PaymentAttempt.Status.EXCEPTION) {
                            return;
                        }
                        String problem = paidResultProblem(order, result);
                        attempt.paid(result.tradeNo(), result.paidAt(), problem != null);
                        if (problem != null) {
                            payments.exception(
                                    order.getId(),
                                    attempt.getId(),
                                    problem,
                                    result,
                                    clock.instant());
                            return;
                        }
                        order.succeed(attempt.getId());
                        if (order.getBookingId() != null) {
                            bookings.confirm(order.getBookingId(), result.paidAt());
                        } else {
                            memberships.activate(order.getMembershipPurchaseId());
                        }
                        operations.audit(
                                order.getUserId(),
                                "PAYMENT_CONFIRMED",
                                "PAYMENT_ORDER",
                                order.getId(),
                                attempt.getOutTradeNo(),
                                clock.instant());
                    } else if (attempt.unresolved()) {
                        switch (result.state()) {
                            case CLOSED -> attempt.close();
                            case PENDING -> attempt.pending(result.checkoutUrl());
                            case UNKNOWN -> attempt.unknown();
                            default -> {}
                        }
                    }
                });
    }

    /** 实际收到资金并不等于可以履约；异常资金保留证据，交由后台复核。 */
    private String paidResultProblem(PaymentOrder order, PaymentGateway.Result result) {
        String problem = null;
        if (result.amount() == null || result.amount().compareTo(order.getAmount()) != 0) {
            problem = "AMOUNT_MISMATCH";
        } else if (result.tradeNo() == null || result.paidAt() == null) {
            problem = "INCOMPLETE_PAID_RESULT";
        } else if (!result.paidAt().isBefore(order.getExpiresAt())) {
            problem = "LATE_PAYMENT";
        } else if (result.paidAt().isAfter(clock.instant().plusSeconds(60))) {
            problem = "INVALID_PAYMENT_TIME";
        } else if (order.getStatus() != PaymentOrder.Status.PENDING) {
            problem = "ORDER_ALREADY_FINAL";
        }
        return problem;
    }

    /** 主动查询与到期关闭共用同一落账入口。 查询/关闭仍未知时保留占房，只有确认终态或从未发起交易时才允许释放。 */
    public void reconcile(long id) {
        List<Work> work =
                tx.execute(
                        transactionStatus -> {
                            var order = payments.order(id);
                            return payments.attempts(id).stream()
                                    .filter(PaymentAttempt::unresolved)
                                    .map(attempt -> work(order, attempt))
                                    .toList();
                        });
        for (Work item : Objects.requireNonNull(work)) {
            if (!item.provider().equals(gateway.provider())) {
                continue;
            }
            try {
                var result = gateway.query(item.command());
                if (!item.command().expiresAt().isAfter(clock.instant())
                        && result.state() != PaymentGateway.State.PAID
                        && result.state() != PaymentGateway.State.CLOSED) {
                    result = gateway.close(item.command());
                }
                apply(item, result);
            } catch (RuntimeException ex) {
                log.warn("Payment reconciliation deferred attempt={}", item.attempt());
            }
        }
        // Re-read under the user lock: an attempt may have been created since the initial query.
        Long owner = tx.execute(transactionStatus -> payments.order(id).getUserId());
        tx.executeWithoutResult(
                transactionStatus -> {
                    users.lock(Objects.requireNonNull(owner));
                    var order = payments.order(id);
                    if (order.getStatus() == PaymentOrder.Status.PENDING
                            && !order.getExpiresAt().isAfter(clock.instant())
                            && payments.attempts(id).stream()
                                    .noneMatch(PaymentAttempt::unresolved)) {
                        order.expire();
                        if (order.getBookingId() != null) {
                            bookings.expire(order.getBookingId());
                        } else {
                            memberships.expire(order.getMembershipPurchaseId());
                        }
                    }
                });
    }

    @Scheduled(fixedDelayString = "${booking.job-delay-ms:60000}")
    public void scheduled() {
        if (!settings.isJobsEnabled()) {
            return;
        }
        long cursor = 0;
        while (true) {
            long after = cursor;
            var ids =
                    Objects.requireNonNull(
                            tx.execute(transactionStatus -> payments.pendingOrders(after, 100)));
            for (long id : ids) {
                try {
                    reconcile(id);
                } catch (RuntimeException ex) {
                    log.error("Payment recovery failed order={}", id, ex);
                }
            }
            if (ids.size() < 100) {
                break;
            }
            cursor = ids.getLast();
        }
    }

    public List<Map<String, Object>> exceptions(int page, int size) {
        return tx.execute(
                transactionStatus -> payments.exceptions(PageResult.offset(page, size), size));
    }

    /** 记录人工复核结论；此操作不会调用退款接口，也不会把预订改为已支付。 */
    public void reviewException(long actor, long id, String note) {
        tx.executeWithoutResult(
                transactionStatus -> {
                    payments.resolveException(id, actor, note, clock.instant());
                    operations.audit(
                            actor,
                            "REVIEW_PAYMENT_EXCEPTION",
                            "PAYMENT_EXCEPTION",
                            id,
                            note,
                            clock.instant());
                });
    }

    private static void owner(PaymentOrder order, long user) {
        if (order.getUserId() != user) {
            throw DomainException.missing();
        }
    }

    private void requireProvider(Work work) {
        if (!work.provider().equals(gateway.provider())) {
            throw DomainException.unavailable("该支付记录所属渠道当前未启用");
        }
    }

    private static PaymentGateway.Result unknown(Work work) {
        return new PaymentGateway.Result(
                work.command().outTradeNo(), PaymentGateway.State.UNKNOWN, null, null, null, null);
    }
}
