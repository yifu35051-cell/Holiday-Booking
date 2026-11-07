package com.holidaybooking.backend.payment.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** HMAC binds the exact callback bytes to a timestamp; only backend workers have this key. */
@Component
@ConditionalOnProperty(name = "booking.payment-mode", havingValue = "wiremock")
public class SandboxEventSignature
        implements com.holidaybooking.backend.payment.domain.SandboxCallbackAuthenticator {
    private final byte[] key;
    private final Clock clock;

    public SandboxEventSignature(
            @Value("${booking.wiremock.callback-secret}") String secret, Clock clock) {
        if (secret.length() < 32 || secret.startsWith("REPLACE_"))
            throw new IllegalArgumentException(
                    "WIREMOCK_CALLBACK_SECRET needs at least 32 random characters");
        key = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public String sign(String timestamp, String body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(
                            mac.doFinal(
                                    (timestamp + "\n" + body).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HMAC unavailable", impossible);
        }
    }

    public boolean verify(String timestamp, String body, String signature) {
        try {
            long seconds = Long.parseLong(timestamp);
            long now = clock.instant().getEpochSecond();
            return seconds >= now - 300
                    && seconds <= now + 300
                    && MessageDigest.isEqual(
                            HexFormat.of().parseHex(sign(timestamp, body)),
                            HexFormat.of().parseHex(signature));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
