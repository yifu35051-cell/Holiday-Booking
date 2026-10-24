package com.holidaybooking.backend.shared.api;

import org.slf4j.MDC;

/** 统一成功响应，附带 traceId，便于将客户端请求与服务端日志对应。 */
public record ApiResponse<T>(T data, String traceId) {
    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, MDC.get("traceId"));
    }
}
