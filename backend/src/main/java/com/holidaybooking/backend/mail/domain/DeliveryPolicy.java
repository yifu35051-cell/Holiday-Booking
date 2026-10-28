package com.holidaybooking.backend.mail.domain;

import java.time.Duration;
import java.time.Instant;

/** 系统通知和个人邮件共用的投递结果策略，不依赖预订、账户或数据库任务模型。 */
public final class DeliveryPolicy {
    private DeliveryPolicy() {}

    public record Update(String state, String detail, Instant nextAttempt, Instant deliveredAt) {}

    /** attempts包含本次尝试；首次失败后等待1、5、30分钟，第4次仍失败则停止。 */
    public static Update next(int attempts, EmailSender.Result result, Instant now) {
        if (attempts < 1) throw new IllegalArgumentException("Delivery attempts must be positive");
        String state = result.outcome().name();
        Instant next = now;
        if (result.outcome() == EmailSender.Outcome.RETRY) {
            if (attempts >= 4) state = "FAILED";
            else next = now.plus(Duration.ofMinutes(new int[] {1, 5, 30}[attempts - 1]));
        }
        boolean accepted =
                result.outcome() == EmailSender.Outcome.SENT
                        || result.outcome() == EmailSender.Outcome.SIMULATED;
        return new Update(state, result.detail(), next, accepted ? now : null);
    }
}
