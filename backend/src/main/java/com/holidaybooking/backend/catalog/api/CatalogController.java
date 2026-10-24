package com.holidaybooking.backend.catalog.api;

import com.holidaybooking.backend.booking.domain.Stay;
import com.holidaybooking.backend.catalog.application.CatalogService;
import com.holidaybooking.backend.shared.api.ApiResponse;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 公开客房查询接口；可用性仅供展示，创建预订时仍需在事务内重新核验。 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CatalogController {
    private final CatalogService catalog;

    @GetMapping("/room-types")
    public ApiResponse<List<CatalogService.TypeView>> types() {
        return ApiResponse.of(catalog.types(false));
    }

    @GetMapping("/room-types/{id}")
    public ApiResponse<CatalogService.TypeView> detail(@PathVariable long id) {
        return ApiResponse.of(catalog.detail(id));
    }

    @GetMapping("/availability")
    public ApiResponse<List<CatalogService.Availability>> availability(
            @RequestParam LocalDate checkIn,
            @RequestParam LocalDate checkOut,
            @RequestParam(defaultValue = "1") int guestsPerRoom,
            @RequestParam(defaultValue = "1") int rooms) {
        return ApiResponse.of(
                catalog.availability(new Stay(checkIn, checkOut), guestsPerRoom, rooms));
    }
}
