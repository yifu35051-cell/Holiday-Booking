package com.holidaybooking.backend.payment.infrastructure;

import com.holidaybooking.backend.payment.domain.PaymentChannelException;
import com.holidaybooking.backend.payment.domain.PaymentGateway;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * WireMock controls the HTTP channel contract and faults; MySQL owns the simulated money. HTTP is
 * always executed before entering the local ledger transaction. This is a local
 * service-virtualization sandbox, not an independent bank or a third-party payment provider.
 */
@Component
@Primary
@ConditionalOnProperty(name = "booking.payment-mode", havingValue = "wiremock")
public class WireMockPaymentGateway implements PaymentGateway {
    private final PersistentSandboxGateway ledger;
    private final ObjectMapper json;
    private final URI base;
    private final com.holidaybooking.backend.shared.application.IntegrationCircuit circuit;
    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public WireMockPaymentGateway(
            PersistentSandboxGateway ledger,
            ObjectMapper json,
            @Value("${booking.wiremock.base-url}") String baseUrl,
            com.holidaybooking.backend.shared.application.IntegrationCircuit circuit) {
        this.circuit = circuit;
        this.ledger = ledger;
        this.json = json;
        base = URI.create(baseUrl);
        // A fixed operator-controlled origin, never a URL supplied by a checkout request.
        if (!Set.of("http", "https").contains(base.getScheme())
                || base.getHost() == null
                || base.getUserInfo() != null
                || base.getQuery() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/"))) {
            throw new IllegalArgumentException("WIREMOCK_BASE_URL must be an HTTP origin");
        }
    }

    public String provider() {
        return "WIREMOCK";
    }

    public boolean asynchronousConfirmation() {
        return true;
    }

    public Result create(Command command) {
        exchange("create", command.outTradeNo());
        return ledger.create(command);
    }

    public Result query(Command command) {
        exchange("query", command.outTradeNo());
        return ledger.query(command);
    }

    public Result close(Command command) {
        exchange("close", command.outTradeNo());
        return ledger.close(command);
    }

    public RefundState refund(RefundCommand command) {
        exchange("refund", command.outTradeNo());
        return ledger.refund(command);
    }

    public void beforeAuthorize(Command command) {
        exchange("authorize", command.outTradeNo());
    }

    private record Request(String operation, String outTradeNo) {}

    private void exchange(String operation, String trade) {
        if (!circuit.acquire("wiremock"))
            throw new PaymentChannelException(
                    PaymentChannelException.Reason.UNAVAILABLE,
                    "Payment channel circuit open; retry later");
        boolean healthy = false;
        long started = System.nanoTime();
        try {
            var request =
                    HttpRequest.newBuilder(base.resolve("/channel/" + operation))
                            .timeout(Duration.ofSeconds(4))
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            json.writeValueAsString(new Request(operation, trade))))
                            .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (Set.of(401, 403, 404).contains(response.statusCode()))
                throw new PaymentChannelException(
                        PaymentChannelException.Reason.CONFIGURATION,
                        "Payment channel credentials or mappings need operator attention");
            if (response.statusCode() != 200 || response.body().length() > 4096)
                throw new PaymentChannelException(
                        PaymentChannelException.Reason.UNAVAILABLE,
                        "Payment sandbox unavailable; query the existing attempt before retrying");
            var data = json.readTree(response.body());
            if (data == null
                    || !data.isObject()
                    || !operation.equals(data.path("operation").asText())
                    || !trade.equals(data.path("outTradeNo").asText()))
                throw new PaymentChannelException(
                        PaymentChannelException.Reason.INVALID_RESPONSE,
                        "Payment sandbox returned an invalid contract");
            healthy = Set.of("ACCEPTED", "DECLINED").contains(data.path("decision").asText());
            if ("DECLINED".equals(data.path("decision").asText()))
                throw new PaymentChannelException(
                        PaymentChannelException.Reason.REJECTED,
                        "Sandbox declined the operation; no new debit was made");
            if (!"ACCEPTED".equals(data.path("decision").asText()))
                throw new PaymentChannelException(
                        PaymentChannelException.Reason.UNAVAILABLE,
                        "Payment sandbox outcome is unknown");
        } catch (tools.jackson.core.JacksonException exception) {
            throw new PaymentChannelException(
                    PaymentChannelException.Reason.INVALID_RESPONSE,
                    "Payment channel returned invalid JSON");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PaymentChannelException(
                    PaymentChannelException.Reason.UNAVAILABLE,
                    "Payment sandbox request interrupted");
        } catch (java.io.IOException exception) {
            throw new PaymentChannelException(
                    PaymentChannelException.Reason.UNAVAILABLE,
                    "Payment sandbox connection failed; refresh the existing attempt");
        } finally {
            circuit.complete("wiremock", healthy);
            circuit.duration("wiremock", System.nanoTime() - started);
        }
    }
}
