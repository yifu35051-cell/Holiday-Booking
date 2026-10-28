package com.holidaybooking.backend.management.api;

import com.holidaybooking.backend.booking.application.BookingService;
import com.holidaybooking.backend.booking.domain.*;
import com.holidaybooking.backend.catalog.application.CatalogService;
import com.holidaybooking.backend.identity.application.IdentityService;
import com.holidaybooking.backend.identity.domain.UserAccount;
import com.holidaybooking.backend.management.application.AuditService;
import com.holidaybooking.backend.membership.application.MembershipService;
import com.holidaybooking.backend.notification.application.NotificationService;
import com.holidaybooking.backend.payment.application.PaymentService;
import com.holidaybooking.backend.shared.api.ApiResponse;
import com.holidaybooking.backend.shared.application.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 后台操作入口，集中执行员工与管理员的权限限制，并委托应用服务处理业务。 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('STAFF','ADMIN')")
public class ManagementController {
    private final CatalogService catalog;
    private final BookingService bookings;
    private final MembershipService memberships;
    private final IdentityService identity;
    private final PaymentService payments;
    private final NotificationService notifications;
    private final AuditService audits;

    /** 新房型默认启用；停用状态通过更新接口显式维护。 */
    public record CreateTypeInput(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Size(max = 2000) String description,
            @Min(1) @Max(20) int capacity,
            @NotNull @DecimalMin("0.01") BigDecimal basePrice) {}

    public record TypeInput(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Size(max = 2000) String description,
            @Min(1) @Max(20) int capacity,
            @NotNull @DecimalMin("0.01") BigDecimal basePrice,
            boolean active) {}

    public record RoomInput(@Positive long roomTypeId, @NotBlank @Size(max = 30) String number) {}

    public record Active(boolean active) {}

    public record RateInput(
            @NotNull LocalDate start,
            @NotNull LocalDate end,
            @NotNull @DecimalMin("0.01") BigDecimal price) {}

    public record BlockInput(
            @NotNull LocalDate start,
            @NotNull LocalDate end,
            @NotBlank @Size(max = 500) String reason) {}

    public record PlanInput(
            @NotBlank @Size(max = 100) String name,
            @NotNull @DecimalMin("0.01") BigDecimal fee,
            @NotNull BigDecimal discountRatio,
            @Min(1) @Max(3650) int durationDays) {}

    public record RoleInput(@NotNull UserAccount.Role role) {}

    public record Review(@NotBlank @Size(max = 1000) String note) {}

    public record Retry(boolean acknowledgeUnknown) {}

    private static long actor(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }

    @GetMapping("/room-types")
    public ApiResponse<List<CatalogService.TypeView>> types() {
        return ApiResponse.of(catalog.types(true));
    }

    @PostMapping("/room-types")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CatalogService.TypeView> createType(
            Authentication authentication, @Valid @RequestBody CreateTypeInput request) {
        return ApiResponse.of(
                catalog.createType(
                        actor(authentication),
                        request.name(),
                        request.description(),
                        request.capacity(),
                        request.basePrice()));
    }

    @PutMapping("/room-types/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CatalogService.TypeView> updateType(
            Authentication authentication,
            @PathVariable long id,
            @Valid @RequestBody TypeInput request) {
        return ApiResponse.of(
                catalog.updateType(
                        actor(authentication),
                        id,
                        request.name(),
                        request.description(),
                        request.capacity(),
                        request.basePrice(),
                        request.active()));
    }

    @PutMapping("/room-types/{id}/rates")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> rates(
            Authentication authentication,
            @PathVariable long id,
            @Valid @RequestBody RateInput request) {
        catalog.rates(
                actor(authentication),
                id,
                new Stay(request.start(), request.end()),
                request.price());
        return ApiResponse.of(null);
    }

    @GetMapping("/rooms")
    public ApiResponse<List<CatalogService.RoomView>> rooms(@RequestParam long roomTypeId) {
        return ApiResponse.of(catalog.rooms(roomTypeId));
    }

    @PostMapping("/rooms")
    public ApiResponse<CatalogService.RoomView> room(
            Authentication authentication, @Valid @RequestBody RoomInput request) {
        return ApiResponse.of(
                catalog.createRoom(actor(authentication), request.roomTypeId(), request.number()));
    }

    @PatchMapping("/rooms/{id}/active")
    public ApiResponse<CatalogService.RoomView> active(
            Authentication authentication, @PathVariable long id, @RequestBody Active request) {
        return ApiResponse.of(catalog.activate(actor(authentication), id, request.active()));
    }

    @DeleteMapping("/rooms/{id}")
    public ApiResponse<CatalogService.RoomView> retire(
            Authentication authentication, @PathVariable long id) {
        return ApiResponse.of(catalog.activate(actor(authentication), id, false));
    }

    @GetMapping("/rooms/{id}/blocks")
    public ApiResponse<List<Map<String, Object>>> blocks(@PathVariable long id) {
        return ApiResponse.of(catalog.blocks(id));
    }

    @PostMapping("/rooms/{id}/blocks")
    public ApiResponse<String> block(
            Authentication authentication,
            @PathVariable long id,
            @Valid @RequestBody BlockInput request) {
        return ApiResponse.of(
                catalog.block(
                        actor(authentication),
                        id,
                        new Stay(request.start(), request.end()),
                        request.reason()));
    }

    @DeleteMapping("/room-blocks/{id}")
    public ApiResponse<Void> unblock(Authentication authentication, @PathVariable long id) {
        catalog.unblock(actor(authentication), id);
        return ApiResponse.of(null);
    }

    @GetMapping("/bookings")
    public ApiResponse<PageResult<BookingService.BookingView>> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Booking.Status status,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(bookings.list(userId, status, from, to, page, size));
    }

    @GetMapping("/bookings/{id}")
    public ApiResponse<BookingService.BookingView> booking(
            Authentication authentication, @PathVariable long id) {
        return ApiResponse.of(bookings.get(actor(authentication), id, true));
    }

    @PostMapping("/membership-plans")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<MembershipService.PlanView> plan(
            Authentication authentication, @Valid @RequestBody PlanInput request) {
        return ApiResponse.of(
                memberships.createPlan(
                        actor(authentication),
                        request.name(),
                        request.fee(),
                        request.discountRatio(),
                        request.durationDays()));
    }

    @DeleteMapping("/membership-plans/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> retirePlan(Authentication authentication, @PathVariable long id) {
        memberships.retirePlan(actor(authentication), id);
        return ApiResponse.of(null);
    }

    @PutMapping("/users/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<IdentityService.UserView> role(
            Authentication authentication,
            @PathVariable long id,
            @Valid @RequestBody RoleInput request) {
        return ApiResponse.of(identity.changeRole(actor(authentication), id, request.role()));
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PageResult<IdentityService.UserView>> users(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(identity.list(page, size));
    }

    @GetMapping("/payment-exceptions")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<Map<String, Object>>> exceptions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(payments.exceptions(page, size));
    }

    @PostMapping("/payment-exceptions/{id}/review")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> review(
            Authentication authentication,
            @PathVariable long id,
            @Valid @RequestBody Review request) {
        payments.reviewException(actor(authentication), id, request.note());
        return ApiResponse.of(null);
    }

    @GetMapping("/notifications")
    public ApiResponse<List<NotificationService.TaskView>> notifications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(notifications.list(page, size));
    }

    @PostMapping("/notifications/{id}/retry")
    public ApiResponse<Void> retry(
            Authentication authentication, @PathVariable long id, @RequestBody Retry request) {
        notifications.retry(actor(authentication), id, request.acknowledgeUnknown());
        return ApiResponse.of(null);
    }

    @GetMapping("/audit-logs")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<AuditService.AuditView>> audit(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(audits.list(page, size));
    }
}
