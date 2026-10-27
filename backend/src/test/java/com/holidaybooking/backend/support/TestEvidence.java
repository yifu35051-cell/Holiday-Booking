package com.holidaybooking.backend.support;

import com.holidaybooking.backend.shared.domain.DomainException;
import java.util.*;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.test.web.servlet.MvcResult;

/** 记录断言使用的真实值，不用预期值冒充实际输出，不记录请求密码或会话凭据。 */
public final class TestEvidence {
    private static final ThreadLocal<List<String>> VALUES = ThreadLocal.withInitial(ArrayList::new);

    private TestEvidence() {}

    static void begin() {
        VALUES.set(new ArrayList<>());
    }

    static List<String> snapshot() {
        return List.copyOf(VALUES.get());
    }

    public static void record(String label, Object value) {
        VALUES.get().add(label + ": " + String.valueOf(value));
    }

    public static <T> T actual(T value) {
        record("实际断言值", value instanceof jakarta.servlet.http.Cookie ? "Cookie 已返回（值隐藏）" : value);
        return value;
    }

    public static ThrowingCallable expectFailure(ThrowingCallable action) {
        return () -> {
            try {
                action.call();
                record("实际异常", "没有抛出异常；后续异常断言应失败");
            } catch (Throwable error) {
                record("实际异常", error.getClass().getSimpleName() + ": " + error.getMessage());
                if (error instanceof DomainException domain) {
                    record("业务错误码", domain.getCode());
                }
                throw error;
            }
        };
    }

    public static void http(MvcResult result) throws Exception {
        String response =
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        response =
                response.replaceAll(
                        "(\"(?:token|password|passwordHash)\"\\s*:\\s*\")[^\"]*(\")",
                        "$1<hidden>$2");
        record(
                result.getRequest().getMethod() + " " + result.getRequest().getRequestURI(),
                "HTTP " + result.getResponse().getStatus() + " " + response);
    }
}
