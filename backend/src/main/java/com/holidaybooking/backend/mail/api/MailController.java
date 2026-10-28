package com.holidaybooking.backend.mail.api;

import com.holidaybooking.backend.mail.application.MailService;
import com.holidaybooking.backend.mail.domain.MailRepository.Folder;
import com.holidaybooking.backend.shared.api.ApiResponse;
import com.holidaybooking.backend.shared.application.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** CUSTOMER、STAFF、ADMIN均可使用；每次操作均按当前登录账户隔离。 */
@RestController
@RequestMapping("/api/v1/mail")
@RequiredArgsConstructor
public class MailController {
    private final MailService mail;

    public record Send(
            @NotBlank @Email @Size(max = 254) String recipientEmail,
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 10000) String body,
            @Pattern(regexp = "M[1-9][0-9]{0,18}") String replyToId) {}

    public record Read(@NotNull Boolean read) {}

    public record Visibility(@NotNull Folder folder, @NotNull Boolean deleted) {}

    public record Retry(@NotNull Boolean acknowledgeUnknown) {}

    public record Count(long count) {}

    @GetMapping("/recipient")
    public ApiResponse<MailService.Recipient> recipient(@RequestParam String email) {
        return ApiResponse.of(mail.recipient(email));
    }

    @PostMapping("/messages")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public ApiResponse<MailService.View> send(
            Authentication auth,
            @Valid @RequestBody Send request,
            @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.of(
                mail.send(
                        actor(auth),
                        request.recipientEmail(),
                        request.subject(),
                        request.body(),
                        request.replyToId(),
                        key));
    }

    @GetMapping("/messages")
    public ApiResponse<PageResult<MailService.View>> list(
            Authentication auth,
            @RequestParam(defaultValue = "INBOX") Folder folder,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(mail.list(actor(auth), folder, unreadOnly, page, size));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Count> unread(Authentication auth) {
        return ApiResponse.of(new Count(mail.unread(actor(auth))));
    }

    @GetMapping("/messages/{id}")
    public ApiResponse<MailService.View> get(Authentication auth, @PathVariable String id) {
        return ApiResponse.of(mail.get(actor(auth), id));
    }

    @PutMapping("/messages/{id}/read")
    public ApiResponse<MailService.View> read(
            Authentication auth, @PathVariable String id, @Valid @RequestBody Read request) {
        return ApiResponse.of(mail.read(actor(auth), id, request.read()));
    }

    @PutMapping("/messages/{id}/visibility")
    public ApiResponse<Void> visibility(
            Authentication auth, @PathVariable String id, @Valid @RequestBody Visibility request) {
        mail.trash(actor(auth), id, request.folder(), request.deleted());
        return ApiResponse.of(null);
    }

    @PostMapping("/messages/{id}/retry")
    public ApiResponse<MailService.View> retry(
            Authentication auth, @PathVariable String id, @Valid @RequestBody Retry request) {
        return ApiResponse.of(mail.retry(actor(auth), id, request.acknowledgeUnknown(), false));
    }

    private static long actor(Authentication auth) {
        return Long.parseLong(auth.getName());
    }
}
