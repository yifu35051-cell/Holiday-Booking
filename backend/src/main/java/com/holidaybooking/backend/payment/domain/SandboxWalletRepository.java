package com.holidaybooking.backend.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 沙箱余额和不可重复的资金流水。写入方必须先锁定账户行，并处于同一事务。 */
public interface SandboxWalletRepository {
    record Account(
            long userId, String pinHash, BigDecimal balance, int failures, Instant lockedUntil) {}

    record Entry(
            long id,
            long userId,
            String kind,
            String reference,
            BigDecimal amount,
            BigDecimal balanceAfter,
            Instant createdAt) {}

    Optional<Account> find(long user);

    void create(long user);

    void setPin(long user, String hash);

    void authentication(long user, int failures, Instant lockedUntil);

    Optional<Entry> byReference(String reference);

    long post(long user, String kind, String reference, BigDecimal amount, Instant now);

    List<Entry> entries(long user, int offset, int size);
}
