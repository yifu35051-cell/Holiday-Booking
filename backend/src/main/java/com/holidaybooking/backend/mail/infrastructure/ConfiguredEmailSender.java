package com.holidaybooking.backend.mail.infrastructure;

import com.holidaybooking.backend.mail.domain.EmailSender;
import com.holidaybooking.backend.shared.config.BookingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.*;
import org.springframework.stereotype.Component;

/** 可配置邮件适配器：本地只记录模拟结果，SMTP 模式才发出实际邮件。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConfiguredEmailSender implements EmailSender {
    private final com.holidaybooking.backend.shared.application.IntegrationCircuit circuit;
    private final BookingProperties settings;
    private final org.springframework.mail.javamail.JavaMailSender sender;

    public Result sendMessage(Message mail) {
        if ("log".equals(settings.getEmailMode())) {
            log.info("Simulated email; no SMTP transmission");
            return new Result(Outcome.SIMULATED, "Local simulation; no email delivered");
        }
        if (!"smtp".equals(settings.getEmailMode())) {
            return new Result(Outcome.FAILED, "Email transport disabled");
        }
        if (settings.getEmailFrom().isBlank()) {
            return new Result(Outcome.FAILED, "MAIL_FROM is required");
        }
        if (!settings.getEmailAllowlist().isEmpty()
                && settings.getEmailAllowlist().stream()
                        .noneMatch(e -> e.equalsIgnoreCase(mail.recipient()))) {
            return new Result(Outcome.FAILED, "Recipient is outside the configured test allowlist");
        }
        String recipient = mail.recipient().toLowerCase(java.util.Locale.ROOT);
        if (recipient.endsWith(".test")
                || recipient.endsWith(".invalid")
                || recipient.endsWith("@example.com")
                || recipient.endsWith("@example.net")
                || recipient.endsWith("@example.org")) {
            return new Result(
                    Outcome.FAILED, "Reserved fixture address: no external SMTP transmission");
        }
        if (!circuit.acquire("smtp"))
            return new Result(Outcome.RETRY, "SMTP circuit open; retry later");
        var message = new SimpleMailMessage();
        message.setFrom(settings.getEmailFrom());
        message.setTo(mail.recipient());
        message.setReplyTo(mail.replyTo());
        message.setSubject(mail.subject());
        message.setText(mail.body());
        long started = System.nanoTime();
        try {
            sender.send(message);
            circuit.complete("smtp", true);
            return new Result(Outcome.SENT, "SMTP accepted; delivery is not guaranteed");
        } catch (MailAuthenticationException | MailPreparationException ex) {
            circuit.complete("smtp", false);
            return new Result(
                    Outcome.FAILED,
                    "SMTP credentials or message preparation rejected; fix configuration before retry");
        } catch (MailException ex) {
            circuit.complete("smtp", false);
            if (hasConnectFailure(ex))
                return new Result(
                        Outcome.RETRY, "SMTP connection not established; bounded retry scheduled");
            return new Result(Outcome.UNKNOWN, "SMTP outcome is uncertain; review before retrying");
        } finally {
            circuit.duration("smtp", System.nanoTime() - started);
        }
    }

    private static boolean hasConnectFailure(Throwable error) {
        for (int depth = 0; error != null && depth < 12; depth++, error = error.getCause()) {
            if (error instanceof java.net.ConnectException
                    || error instanceof java.net.UnknownHostException) return true;
        }
        return false;
    }
}
