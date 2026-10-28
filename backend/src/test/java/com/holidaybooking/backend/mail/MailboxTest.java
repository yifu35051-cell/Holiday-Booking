package com.holidaybooking.backend.mail;

import static com.holidaybooking.backend.support.TestEvidence.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.holidaybooking.backend.identity.domain.UserAccount;
import com.holidaybooking.backend.mail.application.*;
import com.holidaybooking.backend.mail.domain.EmailSender;
import com.holidaybooking.backend.mail.domain.MailRepository.Folder;
import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.support.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** MySQL验证所有权、队列及HTTP权限；SMTP使用替身，真实容器由MailpitSmoke模块单独验证。 */
@AutoConfigureMockMvc
@Tag("channels")
class MailboxTest extends BusinessTestSupport {
    @Autowired MailService mail;
    @Autowired MailDeliveryService delivery;
    @Autowired MockMvc mvc;
    @MockitoBean EmailSender transport;
    long customer, staff, admin, stranger;

    @BeforeEach
    void accountsAndTransport() {
        customer = user();
        staff = user();
        admin = user();
        stranger = user();
        identity.changeRole(customer, staff, UserAccount.Role.STAFF);
        identity.changeRole(customer, admin, UserAccount.Role.ADMIN);
        // 清理当前独立测试库中前一用例的待投递项，保留历史以验证按用户隔离。
        jdbc.sql(
                        "update mail_message set state='FAILED' where state in ('READY','RETRY','SENDING')")
                .update();
        jdbc.sql("update notification_quota set used_count=0").update();
        when(transport.sendMessage(any()))
                .thenReturn(new EmailSender.Result(EmailSender.Outcome.SENT, null));
    }

    private String email(long user) {
        return jdbc.sql("select email from user_account where id=?")
                .param(user)
                .query(String.class)
                .single();
    }

    private MailService.View send(long from, long to) {
        return mail.send(
                from,
                email(to),
                "Arrival question",
                "Please confirm check-in time.",
                null,
                UUID.randomUUID().toString());
    }

    @Test
    @CaseSpec(
            input =
                    "CUSTOMER向STAFF发送主题Arrival question、正文Please confirm check-in time.；STAFF回复；ADMIN向CUSTOMER发信",
            expected = "初始READY且收件箱为空；投递后SENT、未读1；回复关联原信；三角色均可发信，陌生人不能读取")
    void rolesExchangeMailOnlyAfterAcceptance() {
        var first = send(customer, staff);
        assertThat(actual(first.state())).isEqualTo("READY");
        assertThat(actual(mail.unread(staff))).isZero();
        delivery.dispatch();
        assertThat(actual(mail.get(staff, first.id()).state())).isEqualTo("SENT");
        assertThat(actual(mail.unread(staff))).isEqualTo(1);
        verify(transport)
                .sendMessage(
                        new EmailSender.Message(
                                email(customer),
                                email(staff),
                                "Arrival question",
                                "Please confirm check-in time."));
        var reply =
                mail.send(
                        staff,
                        email(customer),
                        "Re: Arrival question",
                        "Check-in is at 15:00.",
                        first.id(),
                        UUID.randomUUID().toString());
        assertThat(actual(reply.replyToId())).isEqualTo(first.id());
        send(admin, customer);
        delivery.dispatch();
        assertThat(actual(mail.unread(customer))).isEqualTo(2);
        assertThatThrownBy(expectFailure(() -> mail.get(stranger, first.id())))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(expectFailure(() -> mail.get(admin, first.id())))
                .isInstanceOf(DomainException.class);
    }

    @Test
    @CaseSpec(
            input = "客户邮件投递给员工；管理员读正文；员工标已读、未读、移入INBOX回收站及恢复；客户删除SENT副本",
            expected = "管理员读取不改变未读；员工已读后0、未读后1；各自删除互不影响；管理员始终可见正文，列表无正文")
    void readTrashAndAdminInspectionAreIndependent() {
        var sent = send(customer, staff);
        delivery.dispatch();
        assertThat(actual(mail.inspect(admin, sent.id()).body()))
                .isEqualTo("Please confirm check-in time.");
        assertThat(actual(mail.unread(staff))).isEqualTo(1);
        mail.read(staff, sent.id(), true);
        assertThat(actual(mail.unread(staff))).isZero();
        mail.read(staff, sent.id(), false);
        assertThat(actual(mail.unread(staff))).isEqualTo(1);
        mail.trash(staff, sent.id(), Folder.INBOX, true);
        assertThat(actual(mail.list(staff, Folder.TRASH, false, 0, 20).total())).isEqualTo(1);
        assertThat(actual(mail.list(customer, Folder.SENT, false, 0, 20).total())).isEqualTo(1);
        mail.trash(staff, sent.id(), Folder.INBOX, false);
        mail.trash(customer, sent.id(), Folder.SENT, true);
        assertThat(actual(mail.list(staff, Folder.INBOX, false, 0, 20).total())).isEqualTo(1);
        assertThat(mail.all(admin, email(customer), null, null, 0, 20).items())
                .allMatch(m -> m.body() == null);
        assertThat(actual(mail.inspect(admin, sent.id()).senderDeleted())).isTrue();
    }

    @Test
    @CaseSpec(
            input = "同幂等键重复提交同一邮件，再改正文重用；未知邮箱、CRLF主题、错误回复对象；同一客户创建第31封邮件",
            expected = "重复返回同一ID且仅1条；变更内容、未知邮箱、注入、错回复均拒绝；每小时最多30封")
    void idempotencyValidationAndRateLimitPreventBadMessages() {
        String key = UUID.randomUUID().toString();
        var first = mail.send(customer, email(staff), "Question", "Hello", null, key);
        assertThat(actual(mail.send(customer, email(staff), "Question", "Hello", null, key).id()))
                .isEqualTo(first.id());
        assertThat(actual(mail.list(customer, Folder.SENT, false, 0, 20).total())).isEqualTo(1);
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        mail.send(
                                                customer,
                                                email(staff),
                                                "Question",
                                                "Changed",
                                                null,
                                                key)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        mail.send(
                                                customer,
                                                "missing@holiday.test",
                                                "Question",
                                                "Hello",
                                                null,
                                                UUID.randomUUID().toString())))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        mail.send(
                                                customer,
                                                email(staff),
                                                "Hi\r\nBcc: other@holiday.test",
                                                "Hello",
                                                null,
                                                UUID.randomUUID().toString())))
                .isInstanceOf(DomainException.class);
        delivery.dispatch();
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        mail.send(
                                                staff,
                                                email(stranger),
                                                "Reply",
                                                "Hello",
                                                first.id(),
                                                UUID.randomUUID().toString())))
                .isInstanceOf(DomainException.class);
        for (int i = 1; i < 30; i++) send(customer, staff);
        assertThatThrownBy(expectFailure(() -> send(customer, staff)))
                .isInstanceOf(DomainException.class);
        assertThat(actual(mail.list(customer, Folder.SENT, false, 0, 100).total())).isEqualTo(30);
    }

    @Test
    @CaseSpec(
            input = "SMTP首次RETRY；推进1分钟后UNKNOWN；尝试不确认和确认重复风险的手动重试；最终SMTP接受",
            expected = "重试到期前不投递；UNKNOWN不自动重发；未确认拒绝，确认后READY，最终SENT且收件人未读1")
    void uncertainDeliveryRequiresExplicitRetry() {
        when(transport.sendMessage(any()))
                .thenReturn(
                        new EmailSender.Result(EmailSender.Outcome.RETRY, "temporary"),
                        new EmailSender.Result(EmailSender.Outcome.UNKNOWN, "timeout"),
                        new EmailSender.Result(EmailSender.Outcome.SENT, null));
        var sent = send(customer, staff);
        delivery.dispatch();
        assertThat(actual(mail.get(customer, sent.id()).state())).isEqualTo("RETRY");
        delivery.dispatch();
        verify(transport, times(1)).sendMessage(any());
        clock.advance(Duration.ofMinutes(1));
        delivery.dispatch();
        assertThat(actual(mail.get(customer, sent.id()).state())).isEqualTo("UNKNOWN");
        delivery.dispatch();
        verify(transport, times(2)).sendMessage(any());
        assertThatThrownBy(expectFailure(() -> mail.retry(customer, sent.id(), false, false)))
                .isInstanceOf(DomainException.class);
        assertThat(actual(mail.retry(customer, sent.id(), true, false).state())).isEqualTo("READY");
        delivery.dispatch();
        assertThat(actual(mail.unread(staff))).isEqualTo(1);
    }

    @Test
    @CaseSpec(
            input = "匿名GET邮箱；客户和员工GET管理员邮箱；管理员GET管理员邮箱；客户POST邮件分别无CSRF、有CSRF",
            expected = "依次401、403、403、200、403、202；成功返回data.state=READY和M前缀ID")
    void httpProtectsRolesCsrfAndAcceptedResponse() throws Exception {
        mvc.perform(get("/api/v1/mail/messages"))
                .andDo(TestEvidence::http)
                .andExpect(status().isUnauthorized());
        for (long actor : new long[] {customer, staff})
            mvc.perform(get("/api/v1/admin/mail/messages").with(auth(actor)))
                    .andDo(TestEvidence::http)
                    .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/mail/messages").with(auth(admin)))
                .andDo(TestEvidence::http)
                .andExpect(status().isOk());
        String body =
                "{\"recipientEmail\":\""
                        + email(staff)
                        + "\",\"subject\":\"Arrival question\",\"body\":\"Please confirm check-in time.\"}";
        mvc.perform(
                        post("/api/v1/mail/messages")
                                .with(auth(customer))
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andDo(TestEvidence::http)
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/mail/messages")
                                .with(auth(customer))
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andDo(TestEvidence::http)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.state").value("READY"))
                .andExpect(jsonPath("$.data.id").value(org.hamcrest.Matchers.startsWith("M")));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor auth(long id) {
        return org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.user(Long.toString(id))
                .roles("CUSTOMER");
    }

    @Test
    @CaseSpec(
            input = "创建客户预订并用mock支付，联系邮箱为该客户注册邮箱；确认通知SMTP返回SENT",
            expected = "确认通知以N前缀显示于客户邮箱，正文可读，可标已读及删除；管理员仍可查，陌生人不可查")
    void bookingNotificationsAppearInTheSameInbox() {
        long roomType = type(admin, 1);
        var quote = quote(customer, roomType);
        var booking =
                bookings.create(
                        customer,
                        Long.parseLong(quote.id()),
                        "Guest",
                        email(customer),
                        "+86 13800000000",
                        UUID.randomUUID().toString());
        pay(customer, booking.paymentOrderId(), "200.10", "Alipay");
        when(transport.sendMessage(any()))
                .thenReturn(new EmailSender.Result(EmailSender.Outcome.SENT, null));
        notifications.dispatch();
        var inbox = mail.list(customer, Folder.INBOX, false, 0, 20);
        assertThat(actual(inbox.total())).isEqualTo(1);
        var notice = inbox.items().getFirst();
        assertThat(actual(notice.id())).startsWith("N");
        assertThat(actual(mail.get(customer, notice.id()).body())).isNotBlank();
        mail.read(customer, notice.id(), true);
        assertThat(actual(mail.unread(customer))).isZero();
        mail.trash(customer, notice.id(), Folder.INBOX, true);
        assertThat(actual(mail.list(customer, Folder.INBOX, false, 0, 20).total())).isZero();
        assertThat(actual(mail.inspect(admin, notice.id()).kind())).isEqualTo("SYSTEM");
        assertThatThrownBy(expectFailure(() -> mail.get(stranger, notice.id())))
                .isInstanceOf(DomainException.class);
    }
}
