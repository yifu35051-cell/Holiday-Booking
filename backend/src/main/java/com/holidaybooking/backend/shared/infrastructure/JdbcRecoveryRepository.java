package com.holidaybooking.backend.shared.infrastructure;

import com.holidaybooking.backend.shared.domain.RecoveryRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leases are committed before network I/O. After 12 failures recovery requires operator release.
 */
@Repository
@RequiredArgsConstructor
public class JdbcRecoveryRepository implements RecoveryRepository {
    private final JdbcClient jdbc;

    @Transactional
    public boolean claim(String key, Instant now) {
        jdbc.sql(
                        "insert ignore into integration_recovery(operation_key,next_attempt_at,source_trace) values(?,?,?)")
                .params(key, JdbcTime.write(now), MDC.get("traceId"))
                .update();
        return jdbc.sql(
                                "update integration_recovery set lease_until=? where operation_key=? "
                                        + "and manual_review=false and next_attempt_at<=? and (lease_until is null or lease_until<?)")
                        .params(
                                JdbcTime.write(now.plusSeconds(120)),
                                key,
                                JdbcTime.write(now),
                                JdbcTime.write(now))
                        .update()
                == 1;
    }

    @Transactional
    public void complete(String key, boolean terminal, boolean healthy, Instant now) {
        if (terminal) {
            jdbc.sql("delete from integration_recovery where operation_key=?").param(key).update();
            return;
        }
        int failures =
                jdbc.sql(
                                "select attempts from integration_recovery where operation_key=? for update")
                        .param(key)
                        .query(Integer.class)
                        .single();
        failures = healthy ? 0 : failures + 1;
        long delay = healthy ? 60 : Math.min(1800, 60L << Math.min(failures - 1, 5));
        jdbc.sql(
                        "update integration_recovery set attempts=?,next_attempt_at=?,lease_until=null,manual_review=? where operation_key=?")
                .params(failures, JdbcTime.write(now.plusSeconds(delay)), failures >= 12, key)
                .update();
    }

    public List<Status> review() {
        return jdbc.sql(
                        "select operation_key,attempts,next_attempt_at,manual_review from integration_recovery where manual_review=true union all select concat('CALLBACK:',id),attempts,next_attempt_at,true from sandbox_payment_event where state='REVIEW' order by next_attempt_at limit 100")
                .query(
                        (rs, n) ->
                                new Status(
                                        rs.getString("operation_key"),
                                        rs.getInt("attempts"),
                                        JdbcTime.read(rs, "next_attempt_at"),
                                        rs.getBoolean("manual_review")))
                .list();
    }

    public void release(String key, Instant now) {
        if (key.startsWith("CALLBACK:")) {
            jdbc.sql(
                            "update sandbox_payment_event set attempts=0,state='READY',next_attempt_at=? where id=? and state='REVIEW'")
                    .params(JdbcTime.write(now), Long.parseLong(key.substring(9)))
                    .update();
            return;
        }
        jdbc.sql(
                        "update integration_recovery set attempts=0,manual_review=false,next_attempt_at=? where operation_key=? and (lease_until is null or lease_until<?)")
                .params(JdbcTime.write(now), key, JdbcTime.write(now))
                .update();
    }
}
