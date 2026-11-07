package com.holidaybooking.backend.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.holidaybooking.backend.payment.domain.PaymentGateway;
import com.holidaybooking.backend.payment.infrastructure.*;
import com.holidaybooking.backend.shared.application.IntegrationCircuit;
import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.support.CaseSpec;
import com.holidaybooking.backend.support.TestEvidence;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** No Docker, database or SMTP. Contract tests exercise actual HTTP and fail-closed behavior. */
@Tag("channels")
class SandboxProtocolTest {
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    @CaseSpec(
            input =
                    "HMAC secret=32 ASCII characters; timestamp=T0; trade=HB123; tampered body/signature and timestamp T0-301s",
            expected =
                    "Original signature accepted; altered body, malformed signature and stale event rejected")
    void callbackRejectsTamperingAndStaleEvents() {
        var signer = new SandboxEventSignature("12345678901234567890123456789012", CLOCK);
        String timestamp = Long.toString(NOW.getEpochSecond());
        String body = "{\"outTradeNo\":\"HB123\"}";
        String signature = signer.sign(timestamp, body);
        assertThat(TestEvidence.actual(signer.verify(timestamp, body, signature))).isTrue();
        assertThat(signer.verify(timestamp, body + " ", signature)).isFalse();
        assertThat(signer.verify(timestamp, body, "not-hex")).isFalse();
        String stale = Long.toString(NOW.minusSeconds(301).getEpochSecond());
        assertThat(signer.verify(stale, body, signer.sign(stale, body))).isFalse();
    }

    @Test
    @CaseSpec(
            input =
                    "Five consecutive channel failures at t=0; retry at t=29999ms and t=30000ms; one probe succeeds",
            expected =
                    "Circuit rejects calls before 30s, admits one half-open probe, rejects concurrent probe, closes on success")
    void circuitAllowsOnlyOneRecoveryProbe() {
        var clock = mock(Clock.class);
        when(clock.millis()).thenReturn(0L);
        var guard = new IntegrationCircuit(clock, new SimpleMeterRegistry());
        for (int i = 0; i < 5; i++) {
            assertThat(guard.acquire("channel")).isTrue();
            guard.complete("channel", false);
        }
        when(clock.millis()).thenReturn(29999L);
        assertThat(guard.acquire("channel")).isFalse();
        when(clock.millis()).thenReturn(30000L);
        assertThat(guard.acquire("channel")).isTrue();
        assertThat(guard.acquire("channel")).isFalse();
        guard.complete("channel", true);
        assertThat(TestEvidence.actual(guard.acquire("channel"))).isTrue();
    }

    @Test
    @CaseSpec(
            input =
                    "POST /channel/create outTradeNo=HB123; server responds HTTP503, then mismatched trade HB999",
            expected =
                    "Both requests rejected; persistent ledger create never called; no fallback payment or debit")
    void httpFailuresAndMismatchedTradesCannotReachLedger() throws Exception {
        var ledger = mock(PersistentSandboxGateway.class);
        var mode = new java.util.concurrent.atomic.AtomicInteger(503);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/channel/create",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] body =
                            "{\"operation\":\"create\",\"outTradeNo\":\"HB999\",\"decision\":\"ACCEPTED\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(mode.get(), body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        try {
            var gateway =
                    new WireMockPaymentGateway(
                            ledger,
                            new ObjectMapper(),
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            new IntegrationCircuit(CLOCK, new SimpleMeterRegistry()));
            var command =
                    new PaymentGateway.Command(
                            "HB123",
                            "Alipay",
                            new BigDecimal("100.00"),
                            NOW.plusSeconds(900),
                            "stay");
            assertThatThrownBy(() -> gateway.create(command)).isInstanceOf(DomainException.class);
            mode.set(200);
            assertThatThrownBy(() -> gateway.create(command)).isInstanceOf(DomainException.class);
            verifyNoInteractions(ledger);
            TestEvidence.actual("503 and mismatched trade both blocked; ledger untouched");
        } finally {
            server.stop(0);
        }
    }
}
