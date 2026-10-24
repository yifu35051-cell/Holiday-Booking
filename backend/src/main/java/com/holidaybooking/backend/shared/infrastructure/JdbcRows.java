package com.holidaybooking.backend.shared.infrastructure;

import java.math.BigDecimal;
import java.util.*;

/** Keeps JDBC-backed management responses consistent with DTOs: IDs and money are strings. */
public final class JdbcRows {
    private JdbcRows() {}

    public static Map<String, Object> publicValues(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach(
                (key, value) -> {
                    String normalized = key.toLowerCase(Locale.ROOT);
                    Object converted = value;
                    if (value instanceof Long number) {
                        converted = number.toString();
                    }
                    if (value instanceof BigDecimal number) {
                        converted = number.toPlainString();
                    }
                    if (value instanceof java.sql.Timestamp time) {
                        converted =
                                time.toLocalDateTime()
                                        .toInstant(java.time.ZoneOffset.UTC)
                                        .toString();
                    }
                    result.put(normalized, converted);
                });
        return result;
    }
}
