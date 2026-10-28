package com.holidaybooking.backend.payment.infrastructure;

import com.holidaybooking.backend.identity.domain.IdentityRepository;
import com.holidaybooking.backend.payment.domain.PaymentGateway;
import com.holidaybooking.backend.payment.domain.SandboxWalletRepository;
import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.shared.infrastructure.JdbcTime;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 本地支付模拟器。渠道状态持久化，重启后仍能查询；仅在 local/test 配置下启用。 */
@Component
@Profile({"local", "test"})
@ConditionalOnProperty(name = "booking.payment-mode", havingValue = "mock")
@RequiredArgsConstructor
public class MockPaymentGateway implements PaymentGateway {
    private final JdbcClient jdbc;
    private final Clock clock;
    private final SandboxWalletRepository wallets;
    private final IdentityRepository users;

    public String provider() {
        return "MOCK";
    }

    @Transactional
    public Result create(Command command) {
        insertIfMissing(command, "PENDING");
        return query(command);
    }

    private void insertIfMissing(Command command, String state) {
        try {
            jdbc.sql(
                            "insert into mock_payment(out_trade_no,method,amount,state,expires_at)"
                                    + " values(?,?,?,?,?)")
                    .params(
                            command.outTradeNo(),
                            command.method(),
                            command.amount(),
                            state,
                            JdbcTime.write(command.expiresAt()))
                    .update();
        } catch (DuplicateKeyException ignored) {
            /* Merchant order number is the provider's idempotency key. */
        }
    }

    public Result query(Command command) {
        return jdbc.sql("select * from mock_payment where out_trade_no=?")
                .param(command.outTradeNo())
                .query(
                        (rs, n) -> {
                            var paid = JdbcTime.read(rs, "paid_at");
                            return new Result(
                                    command.outTradeNo(),
                                    State.valueOf(rs.getString("state")),
                                    rs.getBigDecimal("amount"),
                                    rs.getString("trade_no"),
                                    paid,
                                    "mock://" + command.outTradeNo());
                        })
                .optional()
                .orElse(new Result(command.outTradeNo(), State.UNKNOWN, null, null, null, null));
    }

    @Transactional
    public Result close(Command command) {
        // A tombstone also prevents a delayed create call from opening an already expired order.
        insertIfMissing(command, "CLOSED");
        jdbc.sql("update mock_payment set state='CLOSED' where out_trade_no=? and state='PENDING'")
                .param(command.outTradeNo())
                .update();
        return query(command);
    }

    @Transactional
    public void simulate(Command command) {
        int changed =
                jdbc.sql(
                                "update mock_payment set state='PAID',trade_no=?,paid_at=? where"
                                        + " out_trade_no=? and state='PENDING' and expires_at>?")
                        .params(
                                "MOCK" + command.outTradeNo(),
                                JdbcTime.write(clock.instant()),
                                command.outTradeNo(),
                                JdbcTime.write(clock.instant()))
                        .update();
        if (changed == 0 && query(command).state() != State.PAID) {
            throw DomainException.conflict("MOCK_PAYMENT_STATE", "模拟支付不存在、已关闭或已到期");
        }
    }

    @Transactional
    public RefundState refund(RefundCommand command) {
        // Same lock order as checkout: user first, then channel transaction. Historical seed
        // payments have no wallet debit and must not create money in a newly opened wallet.
        var debit = wallets.byReference("PAYMENT:" + command.outTradeNo());
        if (debit.isPresent()) users.lock(debit.get().userId());
        var paid =
                jdbc.sql(
                                "select amount from mock_payment where out_trade_no=? and state='PAID' for update")
                        .param(command.outTradeNo())
                        .query(java.math.BigDecimal.class)
                        .optional();
        if (paid.isEmpty()) return RefundState.UNKNOWN;
        var prior =
                jdbc.sql(
                                "select out_trade_no,amount from mock_refund where request_no=? for update")
                        .param(command.requestNo())
                        .query(
                                (rs, row) ->
                                        java.util.Map.entry(rs.getString(1), rs.getBigDecimal(2)))
                        .optional();
        if (prior.isPresent()) {
            return prior.get().getKey().equals(command.outTradeNo())
                            && prior.get().getValue().compareTo(command.amount()) == 0
                    ? RefundState.SUCCEEDED
                    : RefundState.UNKNOWN;
        }
        var used =
                jdbc
                        .sql("select amount from mock_refund where out_trade_no=? for update")
                        .param(command.outTradeNo())
                        .query(java.math.BigDecimal.class)
                        .list()
                        .stream()
                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        if (command.amount().signum() <= 0 || used.add(command.amount()).compareTo(paid.get()) > 0)
            return RefundState.UNKNOWN;
        jdbc.sql("insert into mock_refund(request_no,out_trade_no,amount) values(?,?,?)")
                .params(command.requestNo(), command.outTradeNo(), command.amount())
                .update();
        if (debit.isPresent()) {
            wallets.post(
                    debit.get().userId(),
                    "REFUND",
                    "REFUND:" + command.requestNo(),
                    command.amount(),
                    clock.instant());
        }
        return RefundState.SUCCEEDED;
    }
}
