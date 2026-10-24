package com.holidaybooking.backend;

import static org.assertj.core.api.Assertions.*;

import com.holidaybooking.backend.payment.domain.PaymentGateway;
import com.holidaybooking.backend.payment.infrastructure.AlipayProperties;
import com.holidaybooking.backend.payment.infrastructure.AlipaySandboxGateway;
import com.holidaybooking.backend.shared.domain.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

/** 本地生成测试密钥，验证真实 RSA2 验签；不连接支付渠道，也不使用任何真实密钥。 */
class AlipaySignatureTest {
    private AlipaySandboxGateway gateway;
    private PrivateKey signingKey;

    @BeforeEach
    void prepareKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        signingKey = pair.getPrivate();
        var properties = new AlipayProperties();
        properties.setAppId("test-app");
        properties.setSellerId("test-seller");
        properties.setPrivateKey(
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        properties.setPublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        properties.setNotifyUrl("https://example.com/api/v1/payment-callbacks/alipay");
        gateway = new AlipaySandboxGateway(properties, JsonMapper.builder().build());
    }

    private Map<String, String> signedParameters() throws Exception {
        Map<String, String> parameters = new TreeMap<>();
        parameters.put("app_id", "test-app");
        parameters.put("seller_id", "test-seller");
        parameters.put("out_trade_no", "HB-test-transaction");
        parameters.put("trade_no", "2026102212345678");
        parameters.put("total_amount", "100.00");
        parameters.put("trade_status", "TRADE_SUCCESS");
        parameters.put("gmt_payment", "2026-10-22 08:00:00");
        sign(parameters);
        return parameters;
    }

    private void sign(Map<String, String> parameters) throws Exception {
        String canonical =
                parameters.entrySet().stream()
                        .filter(
                                entry ->
                                        !entry.getKey().equals("sign")
                                                && !entry.getKey().equals("sign_type"))
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .collect(Collectors.joining("&"));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signingKey);
        signature.update(canonical.getBytes(StandardCharsets.UTF_8));
        parameters.put("sign", Base64.getEncoder().encodeToString(signature.sign()));
        parameters.put("sign_type", "RSA2");
    }

    @Test
    void validCallbackVerifiesAndParsesChinaTime() throws Exception {
        var result = gateway.verifyCallback(signedParameters());
        assertThat(result.state()).isEqualTo(PaymentGateway.State.PAID);
        assertThat(result.amount()).isEqualByComparingTo("100.00");
        assertThat(result.paidAt()).isEqualTo(Instant.parse("2026-10-22T00:00:00Z"));
    }

    @Test
    void alteredAmountFailsSignatureVerification() throws Exception {
        var parameters = signedParameters();
        parameters.put("total_amount", "0.01");
        assertThatThrownBy(() -> gateway.verifyCallback(parameters))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void evenSignedCallbackMustBelongToConfiguredMerchant() throws Exception {
        var parameters = signedParameters();
        parameters.put("seller_id", "another-seller");
        sign(parameters);
        assertThatThrownBy(() -> gateway.verifyCallback(parameters))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void missingFieldsAndMalformedDatesAreControlledErrors() throws Exception {
        assertThatThrownBy(() -> gateway.verifyCallback(Map.of()))
                .isInstanceOf(DomainException.class);
        var parameters = signedParameters();
        parameters.put("gmt_payment", "not-a-date");
        sign(parameters);
        assertThatThrownBy(() -> gateway.verifyCallback(parameters))
                .isInstanceOf(DomainException.class);
    }
}
