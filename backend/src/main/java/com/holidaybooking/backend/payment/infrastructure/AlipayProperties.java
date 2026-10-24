package com.holidaybooking.backend.payment.infrastructure;

import lombok.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 支付宝沙箱配置。密钥与应用身份从环境变量注入，禁止写入源码。 */
@Getter
@Setter
@ConfigurationProperties("booking.alipay")
public class AlipayProperties {
    private String appId = "";
    private String privateKey = "";
    private String publicKey = "";
    private String sellerId = "";
    private String notifyUrl = "";
    private String gatewayUrl = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
}
