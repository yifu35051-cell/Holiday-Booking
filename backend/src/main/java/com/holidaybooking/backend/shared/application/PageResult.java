package com.holidaybooking.backend.shared.application;

import com.holidaybooking.backend.shared.domain.DomainException;
import java.util.List;

/** 分页响应及边界校验，限制单次查询大小，避免无界扫描。 */
public record PageResult<T>(List<T> items, int page, int size, long total) {
    public static int offset(int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) {
            throw DomainException.invalid("INVALID_PAGINATION", "page须非负，size须在1到100之间");
        }
        return Math.multiplyExact(page, size);
    }
}
