package com.holidaybooking.backend.shared.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;

/**
 * DATETIME has no zone: bind/read UTC fields explicitly, independently of the developer's JVM zone.
 */
public final class JdbcTime {
    private JdbcTime() {}

    public static LocalDateTime write(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant read(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
