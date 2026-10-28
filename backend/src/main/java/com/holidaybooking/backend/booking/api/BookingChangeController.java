package com.holidaybooking.backend.booking.api;

import com.holidaybooking.backend.booking.application.*;
import com.holidaybooking.backend.booking.domain.Stay;
import com.holidaybooking.backend.payment.application.PaymentService;
import com.holidaybooking.backend.shared.api.ApiResponse;
import com.holidaybooking.backend.shared.application.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 变更采用服务端报价 ID；客户端不能提交退款额或补款额。 */
@RestController
@RequestMapping("/api/v1/bookings/{bookingId}")
@RequiredArgsConstructor
public class BookingChangeController {
    private final BookingChangeService changes;
    private final PaymentService payments;

    public record Submit(@Positive long changeQuoteId) {}

    @GetMapping("/changes")
    public ApiResponse<PageResult<BookingChangeService.View>> list(
            Authentication auth,
            @PathVariable long bookingId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(changes.list(Long.parseLong(auth.getName()), bookingId, page, size));
    }

    @PostMapping("/cancellation-quotes")
    public ApiResponse<BookingChangeService.View> cancellationQuote(
            Authentication auth, @PathVariable long bookingId) {
        return ApiResponse.of(changes.quoteCancellation(Long.parseLong(auth.getName()), bookingId));
    }

    @PostMapping("/amendment-quotes")
    public ApiResponse<BookingChangeService.View> amendmentQuote(
            Authentication auth,
            @PathVariable long bookingId,
            @Valid @RequestBody BookingController.QuoteRequest request) {
        return ApiResponse.of(
                changes.quoteAmendment(
                        Long.parseLong(auth.getName()),
                        bookingId,
                        new Stay(request.checkIn(), request.checkOut()),
                        request.rooms().stream()
                                .map(
                                        room ->
                                                new BookingService.Selection(
                                                        room.roomTypeId(),
                                                        room.rooms(),
                                                        room.guestsPerRoom()))
                                .toList()));
    }

    @PostMapping("/cancellations")
    public ApiResponse<BookingChangeService.View> cancel(
            Authentication auth,
            @PathVariable long bookingId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Submit request) {
        // Validate ownership before requesting an external close. A payment that won
        // the race will invalidate the old cancellation amount at submit time.
        if (changes.validateCancellationForClose(
                Long.parseLong(auth.getName()), bookingId, request.changeQuoteId(), key)) {
            payments.prepareCancellation(Long.parseLong(auth.getName()), bookingId);
        }
        return ApiResponse.of(
                changes.submit(
                        Long.parseLong(auth.getName()),
                        bookingId,
                        request.changeQuoteId(),
                        "CANCEL",
                        key));
    }

    @PostMapping("/amendments")
    public ApiResponse<BookingChangeService.View> amend(
            Authentication auth,
            @PathVariable long bookingId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Submit request) {
        return ApiResponse.of(
                changes.submit(
                        Long.parseLong(auth.getName()),
                        bookingId,
                        request.changeQuoteId(),
                        "AMEND",
                        key));
    }

    @GetMapping({"/changes/{id}", "/cancellations/{id}", "/amendments/{id}"})
    public ApiResponse<BookingChangeService.View> get(
            Authentication auth, @PathVariable long bookingId, @PathVariable long id) {
        return ApiResponse.of(changes.get(Long.parseLong(auth.getName()), bookingId, id));
    }
}
