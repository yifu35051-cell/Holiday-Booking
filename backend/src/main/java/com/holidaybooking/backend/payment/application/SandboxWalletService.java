package com.holidaybooking.backend.payment.application;

import com.holidaybooking.backend.identity.domain.IdentityRepository;
import com.holidaybooking.backend.payment.domain.PaymentGateway;
import com.holidaybooking.backend.payment.domain.SandboxWalletRepository;
import com.holidaybooking.backend.payment.domain.SandboxWalletRepository.Account;
import com.holidaybooking.backend.shared.application.PageResult;
import com.holidaybooking.backend.shared.application.RequestFingerprint;
import com.holidaybooking.backend.shared.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 本项目离线钱包沙箱：仅模拟资金，密码不与任何真实支付渠道共享。 */
@Service
@RequiredArgsConstructor
public class SandboxWalletService {
    private static final int MAX_FAILURES = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private final SandboxWalletRepository wallets;
    private final IdentityRepository users;
    private final PasswordEncoder passwords;
    private final PaymentGateway gateway;
    private final OperationStore operations;
    private final TransactionTemplate tx;
    private final Clock clock;

    public record WalletView(
            boolean configured,
            String balance,
            String currency,
            int attemptsRemaining,
            Instant lockedUntil) {}

    public record EntryView(
            String id,
            String kind,
            String reference,
            String amount,
            String balanceAfter,
            Instant createdAt) {}

    public WalletView get(long user) {
        enabled();
        return view(wallets.find(user).orElse(null));
    }

    public List<EntryView> entries(long user, int page, int size) {
        enabled();
        return wallets.entries(user, PageResult.offset(page, size), size).stream()
                .map(
                        e ->
                                new EntryView(
                                        Long.toString(e.id()),
                                        e.kind(),
                                        e.reference(),
                                        e.amount().toPlainString(),
                                        e.balanceAfter().toPlainString(),
                                        e.createdAt()))
                .toList();
    }

    public WalletView setup(long user, String loginPassword, String pin, String confirmation) {
        enabled();
        validatePin(pin, confirmation);
        // Return credential failures from the transaction, then throw after commit. Otherwise
        // a bad password would roll back its own counter and allow unlimited guessing.
        DomainException failure =
                tx.execute(
                        status -> {
                            var account = users.lock(user);
                            if (wallets.find(user).isEmpty()) wallets.create(user);
                            var wallet = wallets.find(user).orElseThrow();
                            if (wallet.pinHash() != null)
                                throw DomainException.conflict(
                                        "PIN_ALREADY_SET", "支付密码已设置，请使用修改密码功能");
                            var error =
                                    authenticate(wallet, loginPassword, account.getPasswordHash());
                            if (error != null) return error;
                            wallets.setPin(user, passwords.encode(pin));
                            audit(user, "SANDBOX_PIN_SET");
                            return null;
                        });
        if (failure != null) throw failure;
        return get(user);
    }

    public WalletView changePin(long user, String oldPin, String pin, String confirmation) {
        validatePin(pin, confirmation);
        authorized(
                user,
                oldPin,
                () -> {
                    wallets.setPin(user, passwords.encode(pin));
                    audit(user, "SANDBOX_PIN_CHANGED");
                });
        return get(user);
    }

    public WalletView credit(long user, String amount, String pin, String key) {
        var value = Money.of(amount).amount();
        if (!Set.of(new BigDecimal("100.00"), new BigDecimal("1000.00"), new BigDecimal("10000.00"))
                .contains(value)) {
            throw DomainException.invalid("SANDBOX_CREDIT_AMOUNT", "模拟充值金额须为100、1000或10000元");
        }
        String hash = RequestFingerprint.hash(key, value.toPlainString());
        authorized(
                user,
                pin,
                () -> {
                    if (operations.replay(user, "SANDBOX_CREDIT", key, hash) != null) return;
                    var balance = wallets.find(user).orElseThrow().balance();
                    if (balance.add(value).compareTo(new BigDecimal("1000000.00")) > 0)
                        throw DomainException.conflict("SANDBOX_BALANCE_LIMIT", "模拟充值后余额不可超过100万元");
                    long entry =
                            wallets.post(
                                    user,
                                    "CREDIT",
                                    "CREDIT:" + UUID.randomUUID(),
                                    value,
                                    clock.instant());
                    operations.remember(user, "SANDBOX_CREDIT", key, hash, entry, clock.instant());
                    audit(user, "SANDBOX_CREDIT");
                });
        return get(user);
    }

    public void pay(long user, String pin, PaymentGateway.Command command) {
        authorized(
                user,
                pin,
                () -> {
                    var result = gateway.query(command);
                    var prior = wallets.byReference("PAYMENT:" + command.outTradeNo());
                    // The trade number is the debit idempotency key, including retries after lost
                    // responses.
                    if (prior.isPresent()) {
                        if (prior.get().userId() != user
                                || result.state() != PaymentGateway.State.PAID
                                || prior.get().amount().negate().compareTo(command.amount()) != 0)
                            throw DomainException.conflict(
                                    "SANDBOX_LEDGER_MISMATCH", "交易与钱包流水不一致，请联系管理员");
                        return;
                    }
                    if (result.state() != PaymentGateway.State.PENDING
                            || !command.expiresAt().isAfter(clock.instant()))
                        throw DomainException.conflict("ORDER_NOT_PAYABLE", "交易已结束、已到期或状态待确认");
                    if (result.amount() == null || result.amount().compareTo(command.amount()) != 0)
                        throw DomainException.conflict("SANDBOX_AMOUNT_MISMATCH", "渠道金额与订单不一致，未扣款");
                    var balance = wallets.find(user).orElseThrow().balance();
                    if (balance.compareTo(command.amount()) < 0)
                        throw DomainException.conflict(
                                "SANDBOX_INSUFFICIENT_FUNDS", "沙箱余额不足，请先模拟充值");
                    // Both use this same database transaction. A failed/closed channel rolls back
                    // the debit.
                    gateway.simulate(command);
                    wallets.post(
                            user,
                            "PAYMENT",
                            "PAYMENT:" + command.outTradeNo(),
                            command.amount().negate(),
                            clock.instant());
                    audit(user, "SANDBOX_PAYMENT_AUTHORIZED");
                });
    }

    private void authorized(long user, String pin, Runnable action) {
        enabled();
        DomainException failure =
                tx.execute(
                        status -> {
                            users.lock(user);
                            var wallet =
                                    wallets.find(user)
                                            .orElseThrow(
                                                    () ->
                                                            DomainException.conflict(
                                                                    "PIN_NOT_SET", "请先设置沙箱支付密码"));
                            if (wallet.pinHash() == null)
                                throw DomainException.conflict("PIN_NOT_SET", "请先设置沙箱支付密码");
                            var error = authenticate(wallet, pin, wallet.pinHash());
                            if (error != null) return error;
                            action.run();
                            return null;
                        });
        if (failure != null) throw failure;
    }

    private DomainException authenticate(Account wallet, String secret, String hash) {
        var now = clock.instant();
        if (wallet.lockedUntil() != null && wallet.lockedUntil().isAfter(now)) return locked();
        int failures = wallet.lockedUntil() == null ? wallet.failures() : 0;
        if (!passwords.matches(secret, hash)) {
            failures++;
            wallets.authentication(
                    wallet.userId(),
                    failures,
                    failures >= MAX_FAILURES ? now.plus(LOCK_DURATION) : null);
            return failures >= MAX_FAILURES
                    ? locked()
                    : new DomainException(
                            DomainException.Kind.FORBIDDEN,
                            "SANDBOX_CREDENTIAL_INVALID",
                            "密码不正确，剩余尝试次数：" + (MAX_FAILURES - failures));
        }
        wallets.authentication(wallet.userId(), 0, null);
        return null;
    }

    private static DomainException locked() {
        return new DomainException(
                DomainException.Kind.RATE_LIMITED, "SANDBOX_WALLET_LOCKED", "验证错误过多，沙箱支付已锁定15分钟");
    }

    private WalletView view(Account wallet) {
        if (wallet == null) return new WalletView(false, "0.00", "CNY", MAX_FAILURES, null);
        boolean activeLock =
                wallet.lockedUntil() != null && wallet.lockedUntil().isAfter(clock.instant());
        int failures = wallet.lockedUntil() != null && !activeLock ? 0 : wallet.failures();
        return new WalletView(
                wallet.pinHash() != null,
                wallet.balance().toPlainString(),
                "CNY",
                MAX_FAILURES - failures,
                activeLock ? wallet.lockedUntil() : null);
    }

    private static void validatePin(String pin, String confirmation) {
        if (pin == null || !pin.matches("[0-9]{6}") || !pin.equals(confirmation))
            throw DomainException.invalid("SANDBOX_PIN_FORMAT", "沙箱支付密码须为6位数字，且两次输入一致");
    }

    private void enabled() {
        if (!gateway.provider().equals("MOCK")) throw DomainException.missing();
    }

    private void audit(long user, String action) {
        operations.audit(user, action, "SANDBOX_WALLET", user, "Sandbox only", clock.instant());
    }
}
