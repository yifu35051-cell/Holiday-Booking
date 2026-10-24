package com.holidaybooking.backend.payment.infrastructure;

import com.holidaybooking.backend.payment.domain.PaymentGateway;
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
}
