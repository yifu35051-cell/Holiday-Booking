package com.holidaybooking.backend.payment.infrastructure;

import com.alipay.api.*;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.*;
import com.holidaybooking.backend.payment.domain.PaymentGateway;
import com.holidaybooking.backend.shared.domain.DomainException;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 支付宝官方 SDK 沙箱适配器，提供预创建、查询、关闭和 RSA2 回调验签。 */
@Component
@ConditionalOnProperty(name = "booking.payment-mode", havingValue = "alipay-sandbox")
@EnableConfigurationProperties(AlipayProperties.class)
public class AlipaySandboxGateway implements PaymentGateway {
    private final AlipayProperties settings;
    private final AlipayClient client;
    private final ObjectMapper json;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public AlipaySandboxGateway(AlipayProperties properties, ObjectMapper json)
            throws AlipayApiException {
        this.settings = properties;
        this.json = json;
        if (List.of(
                        properties.getAppId(),
                        properties.getPrivateKey(),
                        properties.getPublicKey(),
                        properties.getSellerId(),
                        properties.getNotifyUrl())
                .stream()
                .anyMatch(String::isBlank)) {
            throw new IllegalStateException(
                    "Alipay sandbox requires app ID, private/public keys, seller ID and HTTPS"
                            + " notify URL");
        }
        if (!properties
                        .getGatewayUrl()
                        .equals("https://openapi-sandbox.dl.alipaydev.com/gateway.do")
                || !properties.getNotifyUrl().startsWith("https://")) {
            throw new IllegalStateException(
                    "Only the Alipay sandbox gateway and an HTTPS callback are accepted");
        }
        var config = new AlipayConfig();
        config.setServerUrl(properties.getGatewayUrl());
        config.setAppId(properties.getAppId());
        config.setPrivateKey(properties.getPrivateKey());
        config.setAlipayPublicKey(properties.getPublicKey());
        config.setFormat("json");
        config.setCharset("UTF-8");
        config.setSignType("RSA2");
        config.setConnectTimeout(5000);
        config.setReadTimeout(10000);
        client = new DefaultAlipayClient(config);
    }

    public String provider() {
        return "ALIPAY_SANDBOX";
    }

    public Result create(Command command) {
        requireAlipay(command);
        var request = new AlipayTradePrecreateRequest();
        request.setNotifyUrl(settings.getNotifyUrl());
        request.setBizContent(
                json.writeValueAsString(
                        Map.of(
                                "out_trade_no",
                                command.outTradeNo(),
                                "total_amount",
                                command.amount().toPlainString(),
                                "subject",
                                command.subject(),
                                "time_expire",
                                TIME.format(
                                        command.expiresAt().atZone(ZoneId.of("Asia/Shanghai"))))));
        try {
            var response = client.execute(request);
            if (response.isSuccess() && command.outTradeNo().equals(response.getOutTradeNo())) {
                return new Result(
                        command.outTradeNo(),
                        State.PENDING,
                        command.amount(),
                        null,
                        null,
                        response.getQrCode());
            }
            return unknown(command);
        } catch (AlipayApiException ex) {
            return unknown(command);
        }
    }

    public Result query(Command command) {
        requireAlipay(command);
        var request = new AlipayTradeQueryRequest();
        request.setBizContent(
                json.writeValueAsString(Map.of("out_trade_no", command.outTradeNo())));
        try {
            var response = client.execute(request);
            if (!response.isSuccess() || !command.outTradeNo().equals(response.getOutTradeNo())) {
                return unknown(command);
            }
            State state =
                    switch (response.getTradeStatus()) {
                        case "TRADE_SUCCESS", "TRADE_FINISHED" -> State.PAID;
                        case "TRADE_CLOSED" -> State.CLOSED;
                        case "WAIT_BUYER_PAY" -> State.PENDING;
                        default -> State.UNKNOWN;
                    };
            // 直接按支付宝约定的中国时区解析时间，避免 SDK 的 Date 受 JVM 默认时区影响。
            String paidText =
                    json.readTree(response.getBody())
                            .path("alipay_trade_query_response")
                            .path("send_pay_date")
                            .asText(null);
            return new Result(
                    response.getOutTradeNo(),
                    state,
                    new BigDecimal(response.getTotalAmount()),
                    response.getTradeNo(),
                    parsePaidTime(paidText),
                    null);
        } catch (AlipayApiException | RuntimeException ex) {
            return unknown(command);
        }
    }

    public Result close(Command command) {
        requireAlipay(command);
        var request = new AlipayTradeCloseRequest();
        request.setBizContent(
                json.writeValueAsString(Map.of("out_trade_no", command.outTradeNo())));
        try {
            var response = client.execute(request);
            if (response.isSuccess() && command.outTradeNo().equals(response.getOutTradeNo())) {
                return new Result(
                        command.outTradeNo(),
                        State.CLOSED,
                        null,
                        response.getTradeNo(),
                        null,
                        null);
            }
            // TRADE_NOT_EXIST is not proof that an in-flight precreate cannot still arrive.
            return query(command);
        } catch (AlipayApiException ex) {
            return unknown(command);
        }
    }

    public Result verifyCallback(Map<String, String> parameters) {
        for (String field :
                List.of(
                        "app_id",
                        "seller_id",
                        "out_trade_no",
                        "total_amount",
                        "trade_status",
                        "sign_type",
                        "sign")) {
            if (parameters.get(field) == null || parameters.get(field).isBlank()) {
                throw DomainException.invalid("INVALID_CALLBACK", "支付回调缺少必填参数");
            }
        }
        try {
            if (!"RSA2".equals(parameters.get("sign_type"))
                    || !settings.getAppId().equals(parameters.get("app_id"))
                    || !settings.getSellerId().equals(parameters.get("seller_id"))
                    || !AlipaySignature.rsaCheckV1(
                            parameters, settings.getPublicKey(), "UTF-8", "RSA2")) {
                throw DomainException.invalid("INVALID_SIGNATURE", "支付回调验证失败");
            }
            String status = parameters.get("trade_status");
            State state =
                    switch (status == null ? "" : status) {
                        case "TRADE_SUCCESS", "TRADE_FINISHED" -> State.PAID;
                        case "TRADE_CLOSED" -> State.CLOSED;
                        case "WAIT_BUYER_PAY" -> State.PENDING;
                        default -> State.UNKNOWN;
                    };
            Instant paid = parsePaidTime(parameters.get("gmt_payment"));
            return new Result(
                    parameters.get("out_trade_no"),
                    state,
                    new BigDecimal(parameters.get("total_amount")),
                    parameters.get("trade_no"),
                    paid,
                    null);
        } catch (AlipayApiException | IllegalArgumentException | DateTimeException ex) {
            throw DomainException.invalid("INVALID_CALLBACK", "支付回调参数或签名无效");
        }
    }

    private static Instant parsePaidTime(String value) {
        return value == null || value.isBlank()
                ? null
                : LocalDateTime.parse(value, TIME).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    }

    private static Result unknown(Command command) {
        return new Result(command.outTradeNo(), State.UNKNOWN, null, null, null, null);
    }

    private static void requireAlipay(Command command) {
        if (!"Alipay".equals(command.method())) {
            throw DomainException.unavailable("当前沙箱仅支持 Alipay；Wechat 可在本地模拟模式验证");
        }
    }
}
