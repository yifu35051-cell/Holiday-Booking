package com.holidaybooking.backend.membership.api;

import com.holidaybooking.backend.membership.application.MembershipService;
import com.holidaybooking.backend.shared.api.ApiResponse;
import com.holidaybooking.backend.shared.application.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 会员查询与购买接口；会员身份不会授予员工或管理员权限。 */
@RestController
@RequestMapping("/api/v1/membership")
@RequiredArgsConstructor
public class MembershipController {
    private final MembershipService memberships;

    public record Purchase(@Positive long planId) {}

    @GetMapping("/plans")
    public ApiResponse<List<MembershipService.PlanView>> plans() {
        return ApiResponse.of(memberships.plans());
    }

    @GetMapping("/me")
    public ApiResponse<MembershipService.MemberView> me(Authentication auth) {
        return ApiResponse.of(memberships.me(Long.parseLong(auth.getName())));
    }

    @PostMapping("/purchases")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<MembershipService.PurchaseView> purchase(
            Authentication auth,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Purchase input) {
        return ApiResponse.of(
                memberships.purchase(Long.parseLong(auth.getName()), input.planId(), key));
    }

    @GetMapping("/purchases/{id}")
    public ApiResponse<MembershipService.PurchaseView> get(
            Authentication auth, @PathVariable long id) {
        return ApiResponse.of(memberships.purchase(Long.parseLong(auth.getName()), id));
    }

    @GetMapping("/purchases")
    public ApiResponse<PageResult<MembershipService.PurchaseView>> purchases(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(memberships.purchases(Long.parseLong(auth.getName()), page, size));
    }
}
