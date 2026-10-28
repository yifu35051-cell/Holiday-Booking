package com.holidaybooking.backend.mail.application;

import com.holidaybooking.backend.identity.domain.*;
import com.holidaybooking.backend.mail.domain.MailRepository;
import com.holidaybooking.backend.shared.application.*;
import com.holidaybooking.backend.shared.domain.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 每个注册账户使用注册邮箱收发；客户端不能指定发件人或冒用其他角色。 */
@Service
@RequiredArgsConstructor
public class MailService {
    private final MailRepository mail;
    private final IdentityRepository users;
    private final OperationStore operations;
    private final Clock clock;

    public record View(
            String id,
            String kind,
            String senderId,
            String recipientId,
            String senderEmail,
            String recipientEmail,
            String subject,
            String body,
            String replyToId,
            Instant createdAt,
            Instant deliveredAt,
            Instant readAt,
            boolean senderDeleted,
            boolean recipientDeleted,
            String state,
            int attempts,
            String lastError) {}

    public record Recipient(String email, String displayName) {}

    /** 精确邮箱查询，不向普通用户暴露整个用户目录或角色列表。 */
    @Transactional(readOnly = true)
    public Recipient recipient(String email) {
        var account = activeRecipient(email);
        return new Recipient(account.getEmail(), account.getDisplayName());
    }

    @Transactional
    public View send(
            long actor,
            String recipientEmail,
            String subject,
            String body,
            String replyToId,
            String key) {
        var sender = users.lock(actor);
        if (!sender.isEnabled()) throw DomainException.missing();
        validateText(subject, body);
        if (recipientEmail == null) throw DomainException.invalid("MAIL_RECIPIENT", "收件邮箱不能为空");
        String target = UserAccount.normalize(recipientEmail);
        String cleanSubject = subject.strip();
        // 固定字段名和长度编码，正文中的换行不能导致不同请求共用同一指纹。
        String payload = field(target) + field(cleanSubject) + field(body) + field(replyToId);
        String hash = RequestFingerprint.hash(key, payload);
        Long prior = operations.replay(actor, "SEND_MAIL", key, hash);
        if (prior != null) return view(mail.get("M" + prior), actor, false, true);
        var recipient = activeRecipient(target);
        Long reply = null;
        if (replyToId != null) {
            var original = readable(actor, replyToId);
            if (!original.kind().equals("PERSONAL")
                    || !Objects.equals(original.recipientId(), actor)
                    || original.deliveredAt() == null
                    || !Objects.equals(original.senderId(), recipient.getId())) {
                throw DomainException.invalid("MAIL_REPLY", "只能回复本人收到的用户邮件，收件人必须是原发件人");
            }
            reply = number(replyToId);
        }
        // 独立于SMTP全局额度：同一账户每小时最多创建30封，防止持久化队列无限增长。
        if (mail.sentSince(actor, clock.instant().minus(Duration.ofHours(1))) >= 30) {
            throw new DomainException(
                    DomainException.Kind.RATE_LIMITED, "MAIL_RATE_LIMIT", "每小时最多发送30封邮件");
        }
        long id =
                mail.create(
                        actor,
                        recipient.getId(),
                        sender.getEmail(),
                        recipient.getEmail(),
                        cleanSubject,
                        body,
                        reply,
                        clock.instant());
        operations.remember(actor, "SEND_MAIL", key, hash, id, clock.instant());
        operations.audit(
                actor,
                "SEND_MAIL",
                "MAIL",
                id,
                "recipientId=" + recipient.getId(),
                clock.instant());
        return view(mail.get("M" + id), actor, false, true);
    }

    @Transactional(readOnly = true)
    public PageResult<View> list(
            long user, MailRepository.Folder folder, boolean unreadOnly, int page, int size) {
        if (folder == MailRepository.Folder.ALL
                || (unreadOnly && folder != MailRepository.Folder.INBOX)) {
            throw DomainException.invalid(
                    "MAIL_FOLDER", "收发箱支持INBOX/SENT/TRASH；仅INBOX支持unreadOnly");
        }
        var filter = new MailRepository.Filter(user, folder, unreadOnly, null, null, null);
        return page(filter, page, size, user, false);
    }

    @Transactional(readOnly = true)
    public long unread(long user) {
        return mail.count(
                new MailRepository.Filter(
                        user, MailRepository.Folder.INBOX, true, null, null, null));
    }

    @Transactional(readOnly = true)
    public View get(long user, String id) {
        return view(readable(user, id), user, false, true);
    }

    @Transactional
    public View read(long user, String id, boolean read) {
        users.lock(user);
        var message = readable(user, id);
        requireRecipient(user, message);
        mail.read(message, user, read, clock.instant());
        return view(mail.get(id), user, false, true);
    }

    @Transactional
    public void trash(long user, String id, MailRepository.Folder folder, boolean deleted) {
        users.lock(user);
        var message = readable(user, id);
        if (folder == MailRepository.Folder.INBOX) requireRecipient(user, message);
        else if (folder != MailRepository.Folder.SENT || !Objects.equals(message.senderId(), user))
            throw DomainException.missing();
        mail.trash(message, user, folder, deleted);
    }

    @Transactional
    public View retry(long user, String id, boolean acknowledgeUnknown, boolean admin) {
        users.lock(user);
        if (admin) requireAdmin(user);
        validId(id);
        var message = mail.get(id);
        if (!message.kind().equals("PERSONAL")
                || (!admin && !Objects.equals(message.senderId(), user)))
            throw DomainException.missing();
        mail.retry(number(id), clock.instant(), acknowledgeUnknown);
        operations.audit(
                user,
                "RETRY_MAIL",
                "MAIL",
                number(id),
                "acknowledgeUnknown=" + acknowledgeUnknown,
                clock.instant());
        return view(mail.get(id), user, admin, true);
    }

    @Transactional(readOnly = true)
    public PageResult<View> all(
            long admin, String sender, String recipient, String state, int page, int size) {
        requireAdmin(admin);
        if (state != null
                && !Set.of(
                                "READY",
                                "RETRY",
                                "SENDING",
                                "SENT",
                                "SIMULATED",
                                "UNKNOWN",
                                "FAILED",
                                "SKIPPED")
                        .contains(state)) {
            throw DomainException.invalid("MAIL_STATE", "无效投递状态");
        }
        return page(
                new MailRepository.Filter(
                        null,
                        MailRepository.Folder.ALL,
                        false,
                        normalizeFilter(sender),
                        normalizeFilter(recipient),
                        state),
                page,
                size,
                admin,
                true);
    }

    /** 管理员读正文有审计；不会替收件人设置已读。 */
    @Transactional
    public View inspect(long admin, String id) {
        requireAdmin(admin);
        validId(id);
        var message = mail.get(id);
        operations.audit(
                admin,
                "READ_ALL_MAIL",
                message.kind(),
                number(id),
                "mailId=" + id,
                clock.instant());
        return view(message, admin, true, true);
    }

    private PageResult<View> page(
            MailRepository.Filter filter, int page, int size, long actor, boolean admin) {
        int offset = PageResult.offset(page, size);
        // 列表不返回正文；读取正文必须经详情接口，管理员访问因此可审计。
        return new PageResult<>(
                mail.list(filter, offset, size).stream()
                        .map(m -> view(m, actor, admin, false))
                        .toList(),
                page,
                size,
                mail.count(filter));
    }

    private MailRepository.Message readable(long user, String id) {
        validId(id);
        var message = mail.get(id);
        if (!Objects.equals(message.senderId(), user)
                && !(Objects.equals(message.recipientId(), user) && message.deliveredAt() != null))
            throw DomainException.missing();
        return message;
    }

    private void requireRecipient(long user, MailRepository.Message message) {
        if (!Objects.equals(message.recipientId(), user) || message.deliveredAt() == null)
            throw DomainException.missing();
    }

    private UserAccount activeRecipient(String email) {
        if (email == null || email.length() > 254 || email.contains("\r") || email.contains("\n"))
            throw DomainException.invalid("MAIL_RECIPIENT", "收件邮箱无效");
        return users.byEmail(UserAccount.normalize(email))
                .filter(UserAccount::isEnabled)
                .orElseThrow(DomainException::missing);
    }

    private void requireAdmin(long actor) {
        if (users.find(actor)
                .filter(u -> u.isEnabled() && u.getRole() == UserAccount.Role.ADMIN)
                .isEmpty()) {
            throw new DomainException(DomainException.Kind.FORBIDDEN, "FORBIDDEN", "仅管理员可查看全部邮件");
        }
    }

    private static View view(MailRepository.Message m, long actor, boolean admin, boolean body) {
        boolean sender = admin || Objects.equals(m.senderId(), actor);
        boolean recipient = admin || Objects.equals(m.recipientId(), actor);
        return new View(
                m.id(),
                m.kind(),
                text(m.senderId()),
                text(m.recipientId()),
                m.senderEmail(),
                m.recipientEmail(),
                m.subject(),
                body ? m.body() : null,
                m.replyToId(),
                m.createdAt(),
                m.deliveredAt(),
                recipient ? m.readAt() : null,
                sender && m.senderDeleted(),
                recipient && m.recipientDeleted(),
                m.state(),
                m.attempts(),
                sender ? m.lastError() : null);
    }

    private static String text(Long id) {
        return id == null ? null : id.toString();
    }

    private static String field(String text) {
        return text == null ? "-1:" : text.length() + ":" + text;
    }

    private static String normalizeFilter(String email) {
        return email == null ? null : UserAccount.normalize(email);
    }

    private static void validId(String id) {
        if (id == null || !id.matches("[MN][1-9][0-9]{0,18}"))
            throw DomainException.invalid("MAIL_ID", "邮件ID格式为M数字或N数字");
        number(id);
    }

    private static long number(String id) {
        try {
            return Long.parseLong(id.substring(1));
        } catch (RuntimeException invalid) {
            throw DomainException.invalid("MAIL_ID", "邮件ID无效");
        }
    }

    private static void validateText(String subject, String body) {
        if (subject == null
                || subject.isBlank()
                || subject.length() > 200
                || subject.contains("\r")
                || subject.contains("\n")
                || body == null
                || body.isBlank()
                || body.length() > 10000
                || subject.indexOf('\0') >= 0
                || body.indexOf('\0') >= 0) {
            throw DomainException.invalid("MAIL_CONTENT", "主题1至200字符且无换行，正文1至10000字符，仅支持纯文本");
        }
    }
}
