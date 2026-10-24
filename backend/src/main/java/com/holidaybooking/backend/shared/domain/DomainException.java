package com.holidaybooking.backend.shared.domain;

import lombok.Getter;

/** 具有稳定错误码的业务异常，由 API 层统一映射为 HTTP 状态。 */
@Getter
public class DomainException extends RuntimeException {
    public enum Kind {
        INVALID,
        NOT_FOUND,
        CONFLICT,
        FORBIDDEN,
        UNAVAILABLE,
        RATE_LIMITED
    }

    private final String code;
    private final Kind kind;

    public DomainException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public static DomainException invalid(String code, String message) {
        return new DomainException(Kind.INVALID, code, message);
    }

    public static DomainException conflict(String code, String message) {
        return new DomainException(Kind.CONFLICT, code, message);
    }

    public static DomainException missing() {
        return new DomainException(Kind.NOT_FOUND, "NOT_FOUND", "记录不存在或无权访问");
    }

    public static DomainException unavailable(String message) {
        return new DomainException(Kind.UNAVAILABLE, "SERVICE_UNAVAILABLE", message);
    }
}
