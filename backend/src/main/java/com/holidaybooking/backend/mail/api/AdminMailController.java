package com.holidaybooking.backend.mail.api;

import com.holidaybooking.backend.mail.application.MailService;
import com.holidaybooking.backend.shared.api.ApiResponse;
import com.holidaybooking.backend.shared.application.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 管理员可查个人邮件与全部系统通知，包括用户放入回收站的邮件。 */
@RestController
@RequestMapping("/api/v1/admin/mail")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminMailController {
    private final MailService mail;

    @GetMapping("/messages")
    public ApiResponse<PageResult<MailService.View>> list(
            Authentication auth,
            @RequestParam(required = false) String senderEmail,
            @RequestParam(required = false) String recipientEmail,
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(
                mail.all(
                        Long.parseLong(auth.getName()),
                        senderEmail,
                        recipientEmail,
                        state,
                        page,
                        size));
    }

    @GetMapping("/messages/{id}")
    public ApiResponse<MailService.View> get(Authentication auth, @PathVariable String id) {
        return ApiResponse.of(mail.inspect(Long.parseLong(auth.getName()), id));
    }

    @PostMapping("/messages/{id}/retry")
    public ApiResponse<MailService.View> retry(
            Authentication auth,
            @PathVariable String id,
            @Valid @RequestBody MailController.Retry request) {
        return ApiResponse.of(
                mail.retry(Long.parseLong(auth.getName()), id, request.acknowledgeUnknown(), true));
    }
}
