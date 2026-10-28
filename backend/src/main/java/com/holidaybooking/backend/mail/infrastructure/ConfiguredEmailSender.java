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
        var message = new SimpleMailMessage();
        message.setFrom(settings.getEmailFrom());
        message.setTo(mail.recipient());
        message.setReplyTo(mail.replyTo());
        message.setSubject(mail.subject());
        message.setText(mail.body());
        try {
            sender.send(message);
            return new Result(Outcome.SENT, "SMTP accepted; delivery is not guaranteed");
        } catch (MailAuthenticationException | MailPreparationException ex) {
            return new Result(Outcome.RETRY, "SMTP authentication/preparation failed");
        } catch (MailException ex) {
            return new Result(Outcome.UNKNOWN, "SMTP outcome is uncertain; review before retrying");
        }
    }
}
