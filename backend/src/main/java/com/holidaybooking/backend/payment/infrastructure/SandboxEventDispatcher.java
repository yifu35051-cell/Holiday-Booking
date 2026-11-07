package com.holidaybooking.backend.payment.infrastructure;

import com.holidaybooking.backend.shared.config.BookingProperties;
import com.holidaybooking.backend.shared.infrastructure.JdbcTime;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** At-least-once callbacks with committed leases, bounded retries and manual-review state. */
@Component
@Slf4j
@ConditionalOnProperty(name = "booking.payment-mode", havingValue = "wiremock")
public class SandboxEventDispatcher {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final BookingProperties settings;
    private final SandboxEventSignature signatures;
    private final ObjectMapper json;
    private final URI callback;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public SandboxEventDispatcher(
            JdbcClient jdbc,
            TransactionTemplate tx,
            Clock clock,
            BookingProperties settings,
            SandboxEventSignature signatures,
            ObjectMapper json,
            @Value("${booking.wiremock.base-url}") String origin) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.settings = settings;
        this.signatures = signatures;
        this.json = json;
        callback = URI.create(origin).resolve("/api/v1/payment-callbacks/wiremock");
    }

    private record Event(long id, String outTradeNo, int attempts, String sourceTrace) {}

    private record Payload(String outTradeNo) {}

    @Scheduled(fixedDelayString = "${booking.wiremock.event-delay-ms:3000}")
    public void dispatch() {
        if (!settings.isJobsEnabled()) return;
        jdbc.sql(
                        "update sandbox_payment_event set state='REVIEW',lease_until=null where state='SENDING' and attempts>=8 and lease_until<?")
                .param(JdbcTime.write(clock.instant()))
                .update();
        for (int index = 0; index < 20; index++) {
            var now = clock.instant();
            Event event =
                    tx.execute(
                            status -> {
                                var row =
                                        jdbc.sql(
                                                        "select id,out_trade_no,attempts,source_trace from sandbox_payment_event where "
                                                                + "(state='READY' and next_attempt_at<=?) or (state='SENDING' and lease_until<?) "
                                                                + "order by id limit 1 for update skip locked")
                                                .params(JdbcTime.write(now), JdbcTime.write(now))
                                                .query(
                                                        (rs, n) ->
                                                                new Event(
                                                                        rs.getLong(1),
                                                                        rs.getString(2),
                                                                        rs.getInt(3),
                                                                        rs.getString(4)))
                                                .optional();
                                if (row.isEmpty()) return null;
                                var value = row.get();
                                jdbc.sql(
                                                "update sandbox_payment_event set state='SENDING',attempts=attempts+1,lease_until=? where id=?")
                                        .params(JdbcTime.write(now.plusSeconds(30)), value.id())
                                        .update();
                                return value;
                            });
            if (event == null) return;
            boolean success = false;
            try (var trace =
                    org.slf4j.MDC.putCloseable(
                            "traceId",
                            java.util.Objects.toString(
                                    event.sourceTrace(), java.util.UUID.randomUUID().toString()))) {
                String body = json.writeValueAsString(new Payload(event.outTradeNo()));
                String timestamp = Long.toString(now.getEpochSecond());
                var request =
                        HttpRequest.newBuilder(callback)
                                .timeout(Duration.ofSeconds(10))
                                .header("Content-Type", "application/json")
                                .header("X-Sandbox-Time", timestamp)
                                .header("X-Sandbox-Signature", signatures.sign(timestamp, body))
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build();
                success =
                        client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                                == 204;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (Exception exception) {
                log.warn("Sandbox callback deferred event={}", event.id());
            }
            String state = success ? "SENT" : event.attempts() >= 7 ? "REVIEW" : "READY";
            long delay = Math.min(1800, 5L << Math.min(event.attempts(), 8));
            jdbc.sql(
                            "update sandbox_payment_event set state=?,next_attempt_at=?,lease_until=null where id=? and state='SENDING' and attempts=?")
                    .params(
                            state,
                            JdbcTime.write(clock.instant().plusSeconds(delay)),
                            event.id(),
                            event.attempts() + 1)
                    .update();
            if (Thread.currentThread().isInterrupted()) return;
        }
    }
}
