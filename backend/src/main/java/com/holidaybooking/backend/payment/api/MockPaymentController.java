package com.holidaybooking.backend.payment.api;

import com.holidaybooking.backend.payment.application.PaymentService;
import com.holidaybooking.backend.payment.application.SandboxWalletService;
import com.holidaybooking.backend.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 离线沙箱收银台。旧的无密码 /dev/.../succeed 路由不再注册。 */
@RestController
@Profile({"local", "test"})
@ConditionalOnProperty(name = "booking.payment-mode", havingValue = "mock")
@RequestMapping("/api/v1/sandbox")
@RequiredArgsConstructor
public class MockPaymentController {
    private final PaymentService payments;
    private final SandboxWalletService wallet;

    public record Setup(
            @NotBlank @Size(max = 72) String loginPassword,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String pin,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String confirmation) {}

    public record ChangePin(
            @NotBlank @Pattern(regexp = "[0-9]{6}") String oldPin,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String pin,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String confirmation) {}

    public record Authorize(@NotBlank @Pattern(regexp = "[0-9]{6}") String pin) {}

    public record Credit(
            @NotBlank @Size(max = 20) String amount,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String pin) {}

    @GetMapping("/wallet")
    public ApiResponse<SandboxWalletService.WalletView> get(Authentication auth) {
        return ApiResponse.of(wallet.get(Long.parseLong(auth.getName())));
    }

    @PostMapping("/wallet/setup")
    public ApiResponse<SandboxWalletService.WalletView> setup(
            Authentication auth, @Valid @RequestBody Setup input) {
        return ApiResponse.of(
                wallet.setup(
                        Long.parseLong(auth.getName()),
                        input.loginPassword(),
                        input.pin(),
                        input.confirmation()));
    }

    @PutMapping("/wallet/pin")
    public ApiResponse<SandboxWalletService.WalletView> changePin(
            Authentication auth, @Valid @RequestBody ChangePin input) {
        return ApiResponse.of(
                wallet.changePin(
                        Long.parseLong(auth.getName()),
                        input.oldPin(),
                        input.pin(),
                        input.confirmation()));
    }

    @PostMapping("/wallet/credits")
    public ApiResponse<SandboxWalletService.WalletView> credit(
            Authentication auth,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Credit input) {
        return ApiResponse.of(
                wallet.credit(Long.parseLong(auth.getName()), input.amount(), input.pin(), key));
    }

    @GetMapping("/wallet/entries")
    public ApiResponse<List<SandboxWalletService.EntryView>> entries(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(wallet.entries(Long.parseLong(auth.getName()), page, size));
    }

    @PostMapping("/payment-attempts/{id}/authorize")
    public ApiResponse<PaymentService.OrderView> authorize(
            Authentication auth, @PathVariable long id, @Valid @RequestBody Authorize input) {
        return ApiResponse.of(
                payments.authorizeSandbox(Long.parseLong(auth.getName()), id, input.pin()));
    }

    @PostMapping("/payment-attempts/{id}/cancel")
    public ApiResponse<PaymentService.OrderView> cancel(
            Authentication auth, @PathVariable long id) {
        return ApiResponse.of(payments.cancelAttempt(Long.parseLong(auth.getName()), id));
    }
}
