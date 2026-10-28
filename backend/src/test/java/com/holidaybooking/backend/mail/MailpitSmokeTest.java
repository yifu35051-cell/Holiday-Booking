package com.holidaybooking.backend.mail;

import static com.holidaybooking.backend.support.TestEvidence.actual;
import static org.assertj.core.api.Assertions.assertThat;

import com.holidaybooking.backend.support.CaseSpec;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Explicit opt-in local SMTP check; preserves existing messages in Mailpit. */
@Tag("channels")
@EnabledIfSystemProperty(named = "mailpit.smoke", matches = "true")
class MailpitSmokeTest {
    @Test
    @CaseSpec(
            input =
                    "SMTP localhost:1025; HTTP localhost:8025; booking@example.test -> guest@example.test; unique subject mailpit-smoke-UUID",
            expected =
                    "SMTP accepted; search API HTTP 200 contains unique subject and recipient; skipped unless -Dmailpit.smoke=true")
    void sendTestMailToMailpit() throws Exception {
        var sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(Integer.getInteger("mailpit.smtp.port", 1025));
        sender.getJavaMailProperties().put("mail.smtp.auth", "false");
        sender.getJavaMailProperties().put("mail.smtp.starttls.enable", "false");
        sender.getJavaMailProperties().put("mail.smtp.ssl.enable", "false");
        sender.getJavaMailProperties().put("mail.smtp.connectiontimeout", "3000");
        sender.getJavaMailProperties().put("mail.smtp.timeout", "3000");
        sender.getJavaMailProperties().put("mail.smtp.writetimeout", "3000");
        String subject = "mailpit-smoke-" + UUID.randomUUID();
        var message = new SimpleMailMessage();
        message.setFrom("booking@example.test");
        message.setTo("guest@example.test");
        message.setSubject(subject);
        message.setText("Local Mailpit transport verification.");
        sender.send(message);
        var uri =
                URI.create(
                        "http://127.0.0.1:"
                                + Integer.getInteger("mailpit.http.port", 8025)
                                + "/api/v1/search?query=subject%3A"
                                + subject);
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            var response =
                    client.send(
                            HttpRequest.newBuilder(uri)
                                    .timeout(Duration.ofSeconds(5))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(actual(response.statusCode())).isEqualTo(200);
            assertThat(actual(response.body())).contains(subject, "guest@example.test");
        }
    }
}
