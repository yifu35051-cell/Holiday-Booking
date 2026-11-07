package com.holidaybooking.backend.payment.api;

import com.holidaybooking.backend.payment.application.RefundService;
import com.holidaybooking.backend.payment.application.RefundView;
import com.holidaybooking.backend.shared.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 本人退款状态查询；刷新只重用原退款号，不创建新的退款。 */
@RestController
@RequestMapping("/api/v1/refunds")
@RequiredArgsConstructor
public class RefundController {
    private final RefundService refunds;

    @GetMapping("/{id}")
    public ApiResponse<RefundView> get(Authentication auth, @PathVariable long id) {
        return ApiResponse.of(RefundView.of(refunds.get(Long.parseLong(auth.getName()), id)));
    }

    @PostMapping("/{id}/refresh")
    public ApiResponse<RefundView> refresh(Authentication auth, @PathVariable long id) {
        return ApiResponse.of(RefundView.of(refunds.refresh(Long.parseLong(auth.getName()), id)));
    }
}
