package com.holidaybooking.backend.payment.infrastructure;

import com.holidaybooking.backend.payment.domain.SandboxWalletRepository;
import com.holidaybooking.backend.shared.infrastructure.JdbcTime;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcSandboxWalletRepository implements SandboxWalletRepository {
    private final JdbcClient jdbc;

    public Optional<Account> find(long user) {
        return jdbc.sql("select * from sandbox_wallet where user_id=?")
                .param(user)
                .query(
                        (rs, n) ->
                                new Account(
                                        user,
                                        rs.getString("pin_hash"),
                                        rs.getBigDecimal("balance"),
                                        rs.getInt("failed_attempts"),
                                        JdbcTime.read(rs, "locked_until")))
                .optional();
    }

    public void create(long user) {
        jdbc.sql("insert into sandbox_wallet(user_id) values(?)").param(user).update();
    }

    public void setPin(long user, String hash) {
        jdbc.sql("update sandbox_wallet set pin_hash=? where user_id=?")
                .params(hash, user)
                .update();
    }

    public void authentication(long user, int failures, Instant lockedUntil) {
        jdbc.sql("update sandbox_wallet set failed_attempts=?,locked_until=? where user_id=?")
                .param(1, failures)
                .param(2, lockedUntil == null ? null : JdbcTime.write(lockedUntil), Types.TIMESTAMP)
                .param(3, user)
                .update();
    }

    public Optional<Entry> byReference(String reference) {
        return jdbc.sql("select * from sandbox_wallet_entry where reference_no=?")
                .param(reference)
                .query((rs, n) -> entry(rs))
                .optional();
    }

    public long post(long user, String kind, String reference, BigDecimal amount, Instant now) {
        // Caller holds the user lock; balance and immutable entry commit or roll back together.
        jdbc.sql("update sandbox_wallet set balance=balance+? where user_id=?")
                .params(amount, user)
                .update();
        jdbc.sql(
                        "insert into sandbox_wallet_entry(user_id,kind,reference_no,amount,balance_after,created_at)"
                                + " select user_id,?,?,?,balance,? from sandbox_wallet where user_id=?")
                .params(kind, reference, amount, JdbcTime.write(now), user)
                .update();
        return byReference(reference).orElseThrow().id();
    }

    public List<Entry> entries(long user, int offset, int size) {
        return jdbc.sql(
                        "select * from sandbox_wallet_entry where user_id=? order by id desc limit ? offset ?")
                .params(user, size, offset)
                .query((rs, n) -> entry(rs))
                .list();
    }

    private static Entry entry(ResultSet rs) throws SQLException {
        return new Entry(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("kind"),
                rs.getString("reference_no"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("balance_after"),
                JdbcTime.read(rs, "created_at"));
    }
}
