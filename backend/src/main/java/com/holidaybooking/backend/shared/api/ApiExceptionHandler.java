package com.holidaybooking.backend.shared.api;

import com.holidaybooking.backend.payment.domain.PaymentValidator.PaymentAmountMismatchException;
import com.holidaybooking.backend.payment.domain.PaymentValidator.UnsupportedPaymentMethodException;
import com.holidaybooking.backend.shared.domain.DomainException;
import java.util.List;
import org.slf4j.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

/** 统一错误响应：业务错误返回稳定错误码，内部异常不向客户端泄露堆栈。 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record FieldError(String field, String message) {}

    public record ErrorBody(
            String code, String message, List<FieldError> fieldErrors, String traceId) {}

    public static ErrorBody body(String code, String message) {
        return new ErrorBody(code, message, List.of(), MDC.get("traceId"));
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ErrorBody> domain(DomainException e) {
        int status =
                switch (e.getKind()) {
                    case INVALID -> 422;
                    case NOT_FOUND -> 404;
                    case CONFLICT -> 409;
                    case FORBIDDEN -> 403;
                    case UNAVAILABLE -> 503;
                    case RATE_LIMITED -> 429;
                };
        return ResponseEntity.status(status).body(body(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException e) {
        var fields =
                e.getBindingResult().getFieldErrors().stream()
                        .map(f -> new FieldError(f.getField(), f.getDefaultMessage()))
                        .toList();
        return ResponseEntity.status(422)
                .body(new ErrorBody("VALIDATION_FAILED", "请检查请求参数", fields, MDC.get("traceId")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorBody> invalid(IllegalArgumentException e) {
        return ResponseEntity.status(422).body(body("INVALID_ARGUMENT", e.getMessage()));
    }

    @ExceptionHandler(PaymentAmountMismatchException.class)
    ResponseEntity<ErrorBody> paymentAmount(PaymentAmountMismatchException exception) {
        return ResponseEntity.status(422)
                .body(body("PAYMENT_AMOUNT_MISMATCH", exception.getMessage()));
    }

    @ExceptionHandler(UnsupportedPaymentMethodException.class)
    ResponseEntity<ErrorBody> paymentMethod(UnsupportedPaymentMethodException exception) {
        return ResponseEntity.status(422)
                .body(body("UNSUPPORTED_PAYMENT_METHOD", exception.getMessage()));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingRequestHeaderException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class
    })
    ResponseEntity<ErrorBody> malformed(Exception e) {
        return ResponseEntity.badRequest().body(body("INVALID_REQUEST", "请求格式或必填参数错误"));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ErrorBody> authentication(AuthenticationException e) {
        return ResponseEntity.status(401).body(body("AUTHENTICATION_FAILED", "邮箱或密码错误，或账号不可用"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorBody> forbidden(AccessDeniedException e) {
        return ResponseEntity.status(403).body(body("FORBIDDEN", "无权执行此操作"));
    }

    @ExceptionHandler({
        DataIntegrityViolationException.class,
        OptimisticLockingFailureException.class,
        PessimisticLockingFailureException.class
    })
    ResponseEntity<ErrorBody> conflict(Exception e) {
        return ResponseEntity.status(409).body(body("DATA_CONFLICT", "数据已变化或与现有记录冲突，请刷新后重试"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception e) {
        // 保留 Spring MVC 已确定的 404/405/415 等状态，避免将客户端错误误报为 500。
        if (e instanceof org.springframework.web.ErrorResponse response) {
            return ResponseEntity.status(response.getStatusCode())
                    .body(body("HTTP_" + response.getStatusCode().value(), "请求不符合接口要求"));
        }
        log.error("Unexpected request failure; trace={}", MDC.get("traceId"), e);
        return ResponseEntity.internalServerError()
                .body(body("INTERNAL_ERROR", "服务暂时无法完成请求，请凭traceId联系管理员"));
    }
}
