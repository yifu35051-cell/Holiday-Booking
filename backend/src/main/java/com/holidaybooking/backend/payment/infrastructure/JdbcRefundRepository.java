package com.holidaybooking.backend.payment.infrastructure;

import com.holidaybooking.backend.payment.domain.RefundRepository;
import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.shared.infrastructure.JdbcTime;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcRefundRepository implements RefundRepository {
    private final JdbcClient jdbc;
    private static final String SELECT =
            """
            select r.*,o.user_id,a.out_trade_no,a.provider from refund_request r
            join payment_attempt a on a.id=r.attempt_id join payment_order o on o.id=a.payment_order_id
            """;

    private Refund map(ResultSet rs, int row) throws SQLException {
        return new Refund(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getLong("change_id"),
                rs.getString("request_no"),
                rs.getString("out_trade_no"),
                rs.getString("provider"),
                rs.getBigDecimal("amount"),
                rs.getString("state"),
                rs.getString("last_error"));
    }

    public boolean unsettled(long booking) {
        return jdbc.sql(
                                "select count(*) from refund_request r join booking_change c on c.id=r.change_id where c.booking_id=? and r.state<>'SUCCEEDED'")
                        .param(booking)
                        .query(Long.class)
                        .single()
                > 0;
    }

    public void reserve(long change, long booking, BigDecimal amount, Instant now) {
        var credits =
                jdbc.sql(
                                """
                select a.id,o.amount-coalesce((select sum(r.amount) from refund_request r where r.attempt_id=a.id),0) as remaining
                from payment_order o join payment_attempt a on a.id=o.successful_attempt_id
                left join booking_change c on c.id=o.booking_change_id
                where (o.booking_id=? or c.booking_id=?) and o.status='SUCCEEDED' order by o.id
                """)
                        .params(booking, booking)
                        .query((rs, row) -> Map.entry(rs.getLong(1), rs.getBigDecimal(2)))
                        .list();
        BigDecimal remaining = amount;
        for (var credit : credits) {
            if (remaining.signum() == 0) break;
            BigDecimal portion = remaining.min(credit.getValue());
            if (portion.signum() <= 0) continue;
            jdbc.sql(
                            "insert into refund_request(change_id,attempt_id,amount,request_no,created_at) values(?,?,?,?,?)")
                    .params(
                            change,
                            credit.getKey(),
                            portion,
                            "RF" + UUID.randomUUID().toString().replace("-", ""),
                            JdbcTime.write(now))
                    .update();
            remaining = remaining.subtract(portion);
        }
        if (remaining.signum() != 0)
            throw DomainException.conflict("REFUND_BALANCE", "可退余额不足，需人工核对资金");
    }

    public List<Refund> forChange(long change) {
        return jdbc.sql(SELECT + " where r.change_id=? order by r.id")
                .param(change)
                .query(this::map)
                .list();
    }

    public Refund get(long id) {
        return jdbc.sql(SELECT + " where r.id=?")
                .param(id)
                .query(this::map)
                .optional()
                .orElseThrow(DomainException::missing);
    }

    public List<Long> pending(long after, int limit) {
        return jdbc.sql(
                        "select id from refund_request where state<>'SUCCEEDED' and id>? order by id limit ?")
                .params(after, limit)
                .query(Long.class)
                .list();
    }

    public void complete(long id, String state, String detail) {
        jdbc.sql("update refund_request set state=?,last_error=? where id=? and state<>'SUCCEEDED'")
                .params(state, detail, id)
                .update();
    }
}
