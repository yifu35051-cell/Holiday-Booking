package com.holidaybooking.backend.payment.api;

import com.holidaybooking.backend.payment.application.PaymentService;
import com.holidaybooking.backend.shared.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Deliberately unavailable outside local/test, even if someone guesses the route. */
@RestController
@Profile({"local", "test"})
@ConditionalOnProperty(name = "booking.payment-mode", havingValue = "mock")
@RequestMapping("/api/v1/dev/payment-attempts")
@RequiredArgsConstructor
public class MockPaymentController {
    private final PaymentService payments;

    @PostMapping("/{id}/succeed")
    public ApiResponse<PaymentService.OrderView> succeed(
            Authentication auth, @PathVariable long id) {
        return ApiResponse.of(payments.simulate(Long.parseLong(auth.getName()), id));
    }
}
