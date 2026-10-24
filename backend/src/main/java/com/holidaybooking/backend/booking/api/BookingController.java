package com.holidaybooking.backend.booking.api;

import com.holidaybooking.backend.booking.application.BookingService;
import com.holidaybooking.backend.booking.domain.*;
import com.holidaybooking.backend.shared.api.ApiResponse;
import com.holidaybooking.backend.shared.application.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 预订 API，只接收入住条件、报价 ID 和联系人信息，不接收客户端计算的总价。 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BookingController {
    private final BookingService bookings;

    public record Selection(
            @Positive long roomTypeId,
            @Min(1) @Max(10) int rooms,
            @Min(1) @Max(20) int guestsPerRoom) {}

    public record QuoteRequest(
            @NotNull LocalDate checkIn,
            @NotNull LocalDate checkOut,
            @NotEmpty @Size(max = 10) List<@Valid Selection> rooms) {}

    public record CreateRequest(
            @Positive long quoteId,
            @NotBlank @Size(max = 80) String contactName,
            @NotBlank @Email @Size(max = 254) String contactEmail,
            @NotBlank @Pattern(regexp = "[+0-9 ()-]{5,30}") String contactPhone) {}

    @PostMapping("/quotes")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<BookingService.QuoteView> quote(
            Authentication auth, @Valid @RequestBody QuoteRequest request) {
        return ApiResponse.of(
                bookings.quote(
                        Long.parseLong(auth.getName()),
                        new Stay(request.checkIn(), request.checkOut()),
                        request.rooms().stream()
                                .map(
                                        r ->
                                                new BookingService.Selection(
                                                        r.roomTypeId(),
                                                        r.rooms(),
                                                        r.guestsPerRoom()))
                                .toList()));
    }

    @PostMapping("/bookings")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<BookingService.BookingView> create(
            Authentication auth,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateRequest request) {
        return ApiResponse.of(
                bookings.create(
                        Long.parseLong(auth.getName()),
                        request.quoteId(),
                        request.contactName(),
                        request.contactEmail(),
                        request.contactPhone(),
                        key));
    }

    @GetMapping("/bookings/{id}")
    public ApiResponse<BookingService.BookingView> get(Authentication auth, @PathVariable long id) {
        return ApiResponse.of(bookings.get(Long.parseLong(auth.getName()), id, false));
    }

    @GetMapping("/bookings")
    public ApiResponse<PageResult<BookingService.BookingView>> list(
            Authentication auth,
            @RequestParam(required = false) Booking.Status status,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(
                bookings.list(Long.parseLong(auth.getName()), status, from, to, page, size));
    }
}
