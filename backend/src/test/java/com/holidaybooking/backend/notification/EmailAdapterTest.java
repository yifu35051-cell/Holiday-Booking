package com.holidaybooking.backend.notification;

import static com.holidaybooking.backend.support.TestEvidence.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.holidaybooking.backend.notification.domain.*;
import com.holidaybooking.backend.notification.infrastructure.ConfiguredEmailSender;
import com.holidaybooking.backend.shared.config.BookingProperties;
import com.holidaybooking.backend.support.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;

/** 验证邮件适配器分支；SMTP transport 使用 Mockito，不进行真实网络投递。 */
@Tag("channels")
class EmailAdapterTest {
    private final BookingProperties settings = new BookingProperties();
    private final JavaMailSender transport = mock(JavaMailSender.class);
    private final ConfiguredEmailSender adapter = new ConfiguredEmailSender(settings, transport);
    private final NotificationRepository.Task task =
            new NotificationRepository.Task(
                    1,
                    1,
                    1,
                    "CONFIRMATION",
                    "guest@example.com",
                    "subject",
                    "body",
                    "READY",
                    0,
                    Instant.EPOCH,
                    null,
                    null);

    @Test
    @CaseSpec(
            input = "同一邮件任务分别采用log、disabled和smtp但收件人不在允许名单的配置",
            expected = "依次SIMULATED、FAILED、FAILED；均不调用SMTP发送器")
    void localAndRestrictedModesDoNotSendMail() {
        settings.setEmailMode("log");
        assertThat(actual(adapter.send(task).outcome())).isEqualTo(EmailSender.Outcome.SIMULATED);
        settings.setEmailMode("disabled");
        assertThat(actual(adapter.send(task).outcome())).isEqualTo(EmailSender.Outcome.FAILED);
        settings.setEmailMode("smtp");
        settings.setEmailFrom("sender@example.com");
        settings.setEmailAllowlist(List.of("another@example.com"));
        assertThat(actual(adapter.send(task).outcome())).isEqualTo(EmailSender.Outcome.FAILED);
        verifyNoInteractions(transport);
    }

    @Test
    @CaseSpec(
            input = "SMTP发送器依次正常返回、抛认证失败、抛发送结果未知异常",
            expected = "依次SENT、RETRY、UNKNOWN；SENT只代表SMTP接受，不代表最终收件")
    void smtpOutcomesDistinguishAcceptanceRetryAndUncertainty() {
        settings.setEmailMode("smtp");
        settings.setEmailFrom("sender@example.com");
        assertThat(actual(adapter.send(task).outcome())).isEqualTo(EmailSender.Outcome.SENT);
        doThrow(new MailAuthenticationException("test"))
                .when(transport)
                .send(any(SimpleMailMessage.class));
        assertThat(actual(adapter.send(task).outcome())).isEqualTo(EmailSender.Outcome.RETRY);
        doThrow(new MailSendException("test")).when(transport).send(any(SimpleMailMessage.class));
        assertThat(actual(adapter.send(task).outcome())).isEqualTo(EmailSender.Outcome.UNKNOWN);
    }
}
