package com.holidaybooking.backend.management.api;

import com.holidaybooking.backend.shared.api.ApiResponse;
import com.holidaybooking.backend.shared.domain.*;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Operator actions reset a retry budget; they never assert that money has been paid/refunded. */
@RestController
@RequestMapping("/api/v1/admin/integrations/recovery")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class IntegrationRecoveryController {
    private final RecoveryRepository recovery;
    private final OperationStore operations;
    private final Clock clock;

    @GetMapping
    public ApiResponse<List<RecoveryRepository.Status>> list() {
        return ApiResponse.of(recovery.review());
    }

    @PostMapping("/{key}/release")
    @Transactional
    public ApiResponse<Boolean> release(Authentication auth, @PathVariable String key) {
        if (!key.matches("(PAYMENT|REFUND|CALLBACK):[0-9]{1,19}")) throw DomainException.missing();
        recovery.release(key, clock.instant());
        operations.audit(
                Long.parseLong(auth.getName()),
                "RECOVERY_RELEASE",
                "INTEGRATION",
                0,
                key,
                clock.instant());
        return ApiResponse.of(true);
    }
}
