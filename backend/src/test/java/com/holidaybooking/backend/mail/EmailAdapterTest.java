package com.holidaybooking.backend.mail;

import static com.holidaybooking.backend.support.TestEvidence.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.holidaybooking.backend.mail.domain.EmailSender;
import com.holidaybooking.backend.mail.infrastructure.ConfiguredEmailSender;
import com.holidaybooking.backend.shared.config.BookingProperties;
import com.holidaybooking.backend.support.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;

/** 验证邮件适配器分支；SMTP transport 使用 Mockito，不进行真实网络投递。 */
@Tag("channels")
class EmailAdapterTest {
    private final BookingProperties settings = new BookingProperties();
    private final JavaMailSender transport = mock(JavaMailSender.class);
    private final ConfiguredEmailSender adapter =
            new ConfiguredEmailSender(
                    new com.holidaybooking.backend.shared.application.IntegrationCircuit(
                            java.time.Clock.systemUTC(),
                            new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                    settings,
                    transport);
    private final EmailSender.Message message =
            new EmailSender.Message(null, "guest@qq.com", "subject", "body");

    @Test
    @CaseSpec(
            input = "同一邮件任务分别采用log、disabled和smtp但收件人不在允许名单的配置",
            expected = "依次SIMULATED、FAILED、FAILED；均不调用SMTP发送器")
    void localAndRestrictedModesDoNotSendMail() {
        settings.setEmailMode("log");
        assertThat(actual(adapter.sendMessage(message).outcome()))
                .isEqualTo(EmailSender.Outcome.SIMULATED);
        settings.setEmailMode("disabled");
        assertThat(actual(adapter.sendMessage(message).outcome()))
                .isEqualTo(EmailSender.Outcome.FAILED);
        settings.setEmailMode("smtp");
        settings.setEmailFrom("sender@qq.com");
        settings.setEmailAllowlist(List.of("another@qq.com"));
        assertThat(actual(adapter.sendMessage(message).outcome()))
                .isEqualTo(EmailSender.Outcome.FAILED);
        verifyNoInteractions(transport);
    }

    @Test
    @CaseSpec(
            input = "SMTP发送器依次正常返回、抛认证失败、抛发送结果未知异常",
            expected = "依次SENT、FAILED、UNKNOWN；SENT只代表SMTP接受，不代表最终收件")
    void smtpOutcomesDistinguishAcceptanceRetryAndUncertainty() {
        settings.setEmailMode("smtp");
        settings.setEmailFrom("sender@qq.com");
        assertThat(actual(adapter.sendMessage(message).outcome()))
                .isEqualTo(EmailSender.Outcome.SENT);
        doThrow(new MailAuthenticationException("test"))
                .when(transport)
                .send(any(SimpleMailMessage.class));
        assertThat(actual(adapter.sendMessage(message).outcome()))
                .isEqualTo(EmailSender.Outcome.FAILED);
        doThrow(new MailSendException("test")).when(transport).send(any(SimpleMailMessage.class));
        assertThat(actual(adapter.sendMessage(message).outcome()))
                .isEqualTo(EmailSender.Outcome.UNKNOWN);
    }

    @Test
    @CaseSpec(
            input =
                    "smtp模式，MAIL_FROM=system@qq.com；用户customer@qq.com发给staff@qq.com，主题Question、正文Hello",
            expected = "SENT；SMTP From为系统地址、Reply-To为客户地址、To为员工地址，主题正文原样传递")
    void personalMailUsesAuthenticatedReplyToAndConfiguredFrom() {
        settings.setEmailMode("smtp");
        settings.setEmailFrom("system@qq.com");
        assertThat(
                        actual(
                                adapter.sendMessage(
                                                new EmailSender.Message(
                                                        "customer@qq.com",
                                                        "staff@qq.com",
                                                        "Question",
                                                        "Hello"))
                                        .outcome()))
                .isEqualTo(EmailSender.Outcome.SENT);
        var captured = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(transport).send(captured.capture());
        var message = captured.getValue();
        assertThat(actual(message.getFrom())).isEqualTo("system@qq.com");
        assertThat(actual(message.getReplyTo())).isEqualTo("customer@qq.com");
        assertThat(actual(message.getTo())).containsExactly("staff@qq.com");
        assertThat(actual(message.getSubject())).isEqualTo("Question");
        assertThat(actual(message.getText())).isEqualTo("Hello");
    }
}
