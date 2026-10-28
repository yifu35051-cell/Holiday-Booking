package com.holidaybooking.backend.mail.application;

import com.holidaybooking.backend.mail.domain.DeliveryPolicy;
import com.holidaybooking.backend.mail.domain.EmailSender;
import com.holidaybooking.backend.mail.domain.MailRepository;
import com.holidaybooking.backend.shared.config.BookingProperties;
import java.time.*;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 出站邮件使用持久化租约，SMTP调用不持有事务；SMTP不提供恰好一次投递承诺。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailDeliveryService {
    private final MailRepository mail;
    private final EmailSender sender;
    private final BookingProperties settings;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${booking.job-delay-ms:60000}")
    public void scheduled() {
        if (settings.isJobsEnabled()) dispatch();
    }

    public void dispatch() {
        if ("disabled".equals(settings.getEmailMode())) return;
        tx.executeWithoutResult(status -> mail.recover(clock.instant()));
        var ids = Objects.requireNonNull(tx.execute(status -> mail.due(clock.instant(), 50)));
        for (long id : ids) {
            try {
                deliver(id);
            } catch (RuntimeException failure) {
                log.error("Mail delivery deferred id={}", id, failure);
            }
        }
    }

    private void deliver(long id) {
        Instant now = clock.instant();
        var work =
                tx.execute(
                        status ->
                                mail.claim(
                                        id,
                                        now,
                                        now.atZone(settings.getZone()).toLocalDate(),
                                        settings.getEmailDailyLimit()));
        if (work == null) return;
        EmailSender.Result result;
        try {
            result =
                    sender.sendMessage(
                            new EmailSender.Message(
                                    work.senderEmail(),
                                    work.recipientEmail(),
                                    work.subject(),
                                    work.body()));
        } catch (RuntimeException failure) {
            result =
                    new EmailSender.Result(
                            EmailSender.Outcome.UNKNOWN,
                            "SMTP outcome unknown; review before retry");
        }
        var update = DeliveryPolicy.next(work.attempts(), result, clock.instant());
        tx.executeWithoutResult(
                status ->
                        mail.complete(
                                work,
                                update.state(),
                                update.detail(),
                                update.nextAttempt(),
                                update.deliveredAt()));
    }
}
