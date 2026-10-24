package com.holidaybooking.backend.management.application;

import com.holidaybooking.backend.shared.application.PageResult;
import com.holidaybooking.backend.shared.domain.OperationStore;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 后台审计查询用例，将数据库审计记录转换为不丢失 ID 精度的响应。 */
@Service
@RequiredArgsConstructor
public class AuditService {
    private final OperationStore operations;

    public record AuditView(
            String id,
            String actorId,
            String action,
            String resourceType,
            String resourceId,
            String detail,
            Instant createdAt) {}

    @Transactional(readOnly = true)
    public List<AuditView> list(int page, int size) {
        return operations.auditEntries(PageResult.offset(page, size), size).stream()
                .map(
                        entry ->
                                new AuditView(
                                        Long.toString(entry.id()),
                                        entry.actorId() == null ? null : entry.actorId().toString(),
                                        entry.action(),
                                        entry.resourceType(),
                                        Long.toString(entry.resourceId()),
                                        entry.detail(),
                                        entry.createdAt()))
                .toList();
    }
}
