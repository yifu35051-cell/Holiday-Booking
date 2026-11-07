package com.holidaybooking.backend.payment.api;

import com.holidaybooking.backend.payment.application.PaymentService;
import com.holidaybooking.backend.payment.domain.SandboxCallbackAuthenticator;
import com.holidaybooking.backend.shared.domain.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Signed local sandbox callback; no browser session can authorize a payment through this route. */
@RestController
@ConditionalOnProperty(name = "booking.payment-mode", havingValue = "wiremock")
public class WireMockCallbackController {
    private final SandboxCallbackAuthenticator signatures;
    private final PaymentService payments;
    private final ObjectMapper json;

    public WireMockCallbackController(
            SandboxCallbackAuthenticator signatures, PaymentService payments, ObjectMapper json) {
        this.signatures = signatures;
        this.payments = payments;
        this.json = json;
    }

    @PostMapping("/api/v1/payment-callbacks/wiremock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receive(
            @RequestHeader("X-Sandbox-Time") String timestamp,
            @RequestHeader("X-Sandbox-Signature") String signature,
            @RequestBody String body) {
        if (body.length() > 1024 || !signatures.verify(timestamp, body, signature))
            throw new DomainException(
                    DomainException.Kind.FORBIDDEN,
                    "CALLBACK_SIGNATURE",
                    "Invalid sandbox signature");
        var trade = json.readTree(body).path("outTradeNo").asText();
        if (!trade.matches("[A-Za-z0-9_-]{1,80}"))
            throw DomainException.invalid("CALLBACK_CONTRACT", "Invalid sandbox trade number");
        payments.sandboxCallback(trade);
    }
}
