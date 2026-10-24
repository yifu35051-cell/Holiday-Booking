package com.holidaybooking.backend.shared.application;

import com.holidaybooking.backend.shared.domain.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

/** 对幂等请求生成摘要。同一用户复用相同幂等键时，请求内容必须保持一致。 */
public final class RequestFingerprint {
    private RequestFingerprint() {}

    public static String hash(String key, String content) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,100}")) {
            throw DomainException.invalid(
                    "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key须为8到100位字母、数字、下划线或连字符");
        }
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
