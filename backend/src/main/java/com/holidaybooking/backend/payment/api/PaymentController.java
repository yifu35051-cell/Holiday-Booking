package com.holidaybooking.backend.payment.api;

import com.holidaybooking.backend.payment.application.PaymentService;
import com.holidaybooking.backend.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 用户支付接口。请求金额仅用于校验，实际应付金额始终读取服务端订单。 */
@RestController
@RequestMapping("/api/v1/payment-orders")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService payments;

    public record Start(
            @NotBlank @Size(max = 30) String amount, @NotBlank @Size(max = 20) String method) {}

    @GetMapping("/{id}")
    public ApiResponse<PaymentService.OrderView> get(Authentication auth, @PathVariable long id) {
        return ApiResponse.of(payments.get(Long.parseLong(auth.getName()), id));
    }

    @PostMapping("/{id}/attempts")
    public ApiResponse<PaymentService.OrderView> start(
            Authentication auth,
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Start input) {
        return ApiResponse.of(
                payments.start(
                        Long.parseLong(auth.getName()), id, input.amount(), input.method(), key));
    }

    @PostMapping("/{id}/refresh")
    public ApiResponse<PaymentService.OrderView> refresh(
            Authentication auth, @PathVariable long id) {
        return ApiResponse.of(payments.refresh(Long.parseLong(auth.getName()), id));
    }
}
