package com.holidaybooking.backend.shared.infrastructure;

import com.holidaybooking.backend.shared.domain.*;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 幂等记录与操作审计的 JDBC 适配器；与对应业务写入共享事务。 */
@Repository
public class JdbcOperationStore implements OperationStore {
    private final JdbcClient jdbc;

    public JdbcOperationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Long replay(long actor, String operation, String key, String hash) {
        var rows =
                jdbc.sql(
                                "SELECT request_hash, resource_id FROM idempotency_record WHERE"
                                        + " actor_id=? AND operation=? AND request_key=?")
                        .params(actor, operation, key)
                        .query((rs, i) -> new Object[] {rs.getString(1), rs.getLong(2)})
                        .list();
        if (rows.isEmpty()) {
            return null;
        }
        if (!hash.equals(rows.getFirst()[0])) {
            throw DomainException.conflict("IDEMPOTENCY_CONFLICT", "同一幂等键不能用于不同请求");
        }
        return (Long) rows.getFirst()[1];
    }

    @Override
    public void remember(
            long actor, String operation, String key, String hash, long resource, Instant now) {
        jdbc.sql(
                        "INSERT INTO"
                                + " idempotency_record(actor_id,operation,request_key,request_hash,resource_id,created_at)"
                                + " VALUES(?,?,?,?,?,?)")
                .params(actor, operation, key, hash, resource, JdbcTime.write(now))
                .update();
    }

    @Override
    public void audit(Long actor, String action, String type, long id, String detail, Instant now) {
        jdbc.sql(
                        "INSERT INTO"
                                + " audit_log(actor_id,action,resource_type,resource_id,detail,created_at)"
                                + " VALUES(?,?,?,?,?,?)")
                .params(actor, action, type, id, detail, JdbcTime.write(now))
                .update();
    }

    @Override
    public List<AuditEntry> auditEntries(int offset, int size) {
        return jdbc.sql("SELECT * FROM audit_log ORDER BY id DESC LIMIT ? OFFSET ?")
                .params(size, offset)
                .query(
                        (rs, i) ->
                                new AuditEntry(
                                        rs.getLong("id"),
                                        rs.getObject("actor_id", Long.class),
                                        rs.getString("action"),
                                        rs.getString("resource_type"),
                                        rs.getLong("resource_id"),
                                        rs.getString("detail"),
                                        JdbcTime.read(rs, "created_at")))
                .list();
    }
}
