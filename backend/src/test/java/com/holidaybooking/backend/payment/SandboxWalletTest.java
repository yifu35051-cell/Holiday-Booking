package com.holidaybooking.backend.payment;

import static com.holidaybooking.backend.support.TestEvidence.*;
import static org.assertj.core.api.Assertions.*;

import com.holidaybooking.backend.booking.application.BookingChangeService;
import com.holidaybooking.backend.payment.application.*;
import com.holidaybooking.backend.payment.domain.PaymentGateway;
import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.support.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

/** 独立MySQL验证真实钱包事务；不连接外部支付商，不使用工作数据库。 */
@Tag("channels")
class SandboxWalletTest extends BusinessTestSupport {
    @Autowired private SandboxWalletService wallet;
    @Autowired private BookingChangeService changes;
    @Autowired private RefundService refunds;
    @Autowired private PaymentGateway gateway;
    private static final String PIN = "824631";

    private long fundedUser() {
        long user = user();
        wallet.setup(user, "test-password-123", PIN, PIN);
        wallet.credit(user, "1000.00", PIN, UUID.randomUUID().toString());
        return user;
    }

    private PaymentService.OrderView pending(long user) {
        long type = type(user, 1);
        var booking =
                book(user, Long.parseLong(quote(user, type).id()), UUID.randomUUID().toString());
        return payments.start(
                user,
                Long.parseLong(booking.paymentOrderId()),
                booking.totalAmount(),
                "Alipay",
                UUID.randomUUID().toString());
    }

    @Test
    @CaseSpec(
            input = "新用户先用错误登录密码初始化，再用test-password-123设置824631",
            expected = "错误不配置钱包且失败次数保留；正确后余额0.00，数据库仅BCrypt哈希")
    void setupVerifiesLoginAndNeverStoresPlainPin() {
        long user = user();
        assertThatThrownBy(expectFailure(() -> wallet.setup(user, "wrong-password", PIN, PIN)))
                .isInstanceOf(DomainException.class);
        assertThat(actual(wallet.get(user).configured())).isFalse();
        assertThat(actual(wallet.get(user).attemptsRemaining())).isEqualTo(4);
        assertThat(actual(wallet.setup(user, "test-password-123", PIN, PIN).balance()))
                .isEqualTo("0.00");
        String hash =
                jdbc.sql("select pin_hash from sandbox_wallet where user_id=?")
                        .param(user)
                        .query(String.class)
                        .single();
        assertThat(hash).startsWith("$2").isNotEqualTo(PIN);
        assertThatThrownBy(expectFailure(() -> wallet.setup(user, "test-password-123", PIN, PIN)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    @CaseSpec(input = "同一充值键请求1000.00两次，再复用键改为100.00", expected = "余额1000.00、充值流水1条；不同金额复用键被拒绝")
    void creditsAreIdempotentAndBoundToAmount() {
        long user = user();
        wallet.setup(user, "test-password-123", PIN, PIN);
        String key = UUID.randomUUID().toString();
        wallet.credit(user, "1000.00", PIN, key);
        assertThat(actual(wallet.credit(user, "1000.00", PIN, key).balance())).isEqualTo("1000.00");
        assertThat(actual(wallet.entries(user, 0, 20).size())).isEqualTo(1);
        assertThatThrownBy(expectFailure(() -> wallet.credit(user, "100.00", PIN, key)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    @CaseSpec(
            input = "已设置824631；连续5次用111111充值，随后用正确密码，推进时钟16分钟再充值100.00",
            expected = "第5次及锁定期返回SANDBOX_WALLET_LOCKED；解锁后余额1100.00，剩余次数5")
    void wrongPinCounterCommitsAndUnlocksAfterTimeout() {
        long user = fundedUser();
        for (int n = 0; n < 5; n++) {
            assertThatThrownBy(
                            expectFailure(
                                    () ->
                                            wallet.credit(
                                                    user,
                                                    "100.00",
                                                    "111111",
                                                    UUID.randomUUID().toString())))
                    .isInstanceOf(DomainException.class);
        }
        assertThat(actual(wallet.get(user).attemptsRemaining())).isZero();
        assertThat(actual(wallet.get(user).lockedUntil()))
                .isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        wallet.credit(
                                                user, "100.00", PIN, UUID.randomUUID().toString())))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("SANDBOX_WALLET_LOCKED"));
        clock.advance(Duration.ofMinutes(16));
        assertThat(
                        actual(
                                wallet.credit(user, "100.00", PIN, UUID.randomUUID().toString())
                                        .balance()))
                .isEqualTo("1100.00");
        assertThat(actual(wallet.get(user).attemptsRemaining())).isEqualTo(5);
    }

    @Test
    @CaseSpec(
            input = "余额1000.00，待付200.10；两线程同时授权同一支付尝试824631",
            expected = "两请求均读到SUCCEEDED；余额799.90；PAYMENT流水仅1条")
    void concurrentAuthorizationDebitsOnlyOnce() throws Exception {
        long user = fundedUser();
        var payment = pending(user);
        long attempt = Long.parseLong(payment.attempts().getFirst().id());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<String> pay =
                    () -> {
                        gate.await();
                        return payments.authorizeSandbox(user, attempt, PIN).status();
                    };
            var a = pool.submit(pay);
            var b = pool.submit(pay);
            gate.countDown();
            assertThat(actual(a.get(30, TimeUnit.SECONDS))).isEqualTo("SUCCEEDED");
            assertThat(actual(b.get(30, TimeUnit.SECONDS))).isEqualTo("SUCCEEDED");
        }
        assertThat(actual(wallet.get(user).balance())).isEqualTo("799.90");
        assertThat(
                        actual(
                                wallet.entries(user, 0, 20).stream()
                                        .filter(e -> e.kind().equals("PAYMENT"))
                                        .count()))
                .isEqualTo(1);
    }

    @Test
    @CaseSpec(
            input = "零余额用户应付200.10；尝试授权；另一用户尝试授权该订单；最后推进16分钟再授权",
            expected = "分别拒绝余额不足、越权、过期；订单未成功；无扣款流水")
    void insufficientFundsOwnershipAndExpiryCannotPay() {
        long user = user();
        wallet.setup(user, "test-password-123", PIN, PIN);
        var payment = pending(user);
        long attempt = Long.parseLong(payment.attempts().getFirst().id());
        assertThatThrownBy(expectFailure(() -> payments.authorizeSandbox(user, attempt, PIN)))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("SANDBOX_INSUFFICIENT_FUNDS"));
        long other = fundedUser();
        assertThatThrownBy(expectFailure(() -> payments.authorizeSandbox(other, attempt, PIN)))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("NOT_FOUND"));
        clock.advance(Duration.ofMinutes(16));
        assertThatThrownBy(expectFailure(() -> payments.authorizeSandbox(user, attempt, PIN)))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ORDER_NOT_PAYABLE"));
        assertThat(actual(wallet.entries(user, 0, 20))).isEmpty();
    }

    @Test
    @CaseSpec(
            input = "200.10待付尝试取消，再授权旧尝试，再用Wechat创建新尝试付款",
            expected = "旧尝试CLOSED且不能扣款；新尝试成功；余额799.90")
    void cancelledAttemptCanBeReplacedButNeverCharged() {
        long user = fundedUser();
        var payment = pending(user);
        long attempt = Long.parseLong(payment.attempts().getFirst().id());
        assertThat(actual(payments.cancelAttempt(user, attempt).attempts().getFirst().status()))
                .isEqualTo("CLOSED");
        assertThatThrownBy(expectFailure(() -> payments.authorizeSandbox(user, attempt, PIN)))
                .isInstanceOf(DomainException.class);
        var next =
                payments.start(
                        user,
                        Long.parseLong(payment.id()),
                        payment.amount(),
                        "Wechat",
                        UUID.randomUUID().toString());
        long nextId =
                Long.parseLong(
                        next.attempts().stream()
                                .filter(a -> a.method().equals("Wechat"))
                                .findFirst()
                                .orElseThrow()
                                .id());
        assertThat(actual(payments.authorizeSandbox(user, nextId, PIN).status()))
                .isEqualTo("SUCCEEDED");
        assertThat(actual(wallet.get(user).balance())).isEqualTo("799.90");
    }

    @Test
    @CaseSpec(input = "原密码824631修改为635284；分别用旧、新密码充值100.00", expected = "旧密码拒绝，新密码成功；余额1100.00")
    void changePinInvalidatesOldSecret() {
        long user = fundedUser();
        wallet.changePin(user, PIN, "635284", "635284");
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        wallet.credit(
                                                user, "100.00", PIN, UUID.randomUUID().toString())))
                .isInstanceOf(DomainException.class);
        assertThat(
                        actual(
                                wallet.credit(
                                                user,
                                                "100.00",
                                                "635284",
                                                UUID.randomUUID().toString())
                                        .balance()))
                .isEqualTo("1100.00");
    }

    @Test
    @CaseSpec(
            input = "余额1000.00支付预订200.10；24小时前取消，重复刷新退款并重放渠道退款",
            expected = "退款SUCCEEDED，余额恢复1000.00；REFUND流水1条，不重复入账")
    void bookingRefundCreditsOriginalWalletOnlyOnce() {
        long user = fundedUser();
        long type = type(user, 1);
        var booking =
                book(user, Long.parseLong(quote(user, type).id()), UUID.randomUUID().toString());
        var payment =
                payments.start(
                        user,
                        Long.parseLong(booking.paymentOrderId()),
                        booking.totalAmount(),
                        "Alipay",
                        UUID.randomUUID().toString());
        payments.authorizeSandbox(user, Long.parseLong(payment.attempts().getFirst().id()), PIN);
        var cancellation = changes.quoteCancellation(user, Long.parseLong(booking.id()));
        var change =
                changes.submit(
                        user,
                        Long.parseLong(booking.id()),
                        Long.parseLong(cancellation.id()),
                        "CANCEL",
                        UUID.randomUUID().toString());
        var refund = change.refunds().getFirst();
        assertThat(actual(refunds.refresh(user, refund.id()).state())).isEqualTo("SUCCEEDED");
        refunds.refresh(user, refund.id());
        gateway.refund(
                new PaymentGateway.RefundCommand(
                        refund.requestNo(), refund.outTradeNo(), refund.amount()));
        assertThat(actual(wallet.get(user).balance())).isEqualTo("1000.00");
        assertThat(
                        actual(
                                wallet.entries(user, 0, 20).stream()
                                        .filter(e -> e.kind().equals("REFUND"))
                                        .count()))
                .isEqualTo(1);
    }

    @Test
    @CaseSpec(
            input = "余额1000.00，购买默认199.00会员；授权同一支付两次",
            expected = "付款前无会员，付款后激活；重复授权不延长有效期；余额801.00")
    void membershipRequiresWalletPaymentAndActivatesOnce() {
        long user = fundedUser();
        var purchase = memberships.purchase(user, 1, UUID.randomUUID().toString());
        assertThat(actual(memberships.me(user).active())).isFalse();
        var payment =
                payments.start(
                        user,
                        Long.parseLong(purchase.paymentOrderId()),
                        purchase.fee(),
                        "Wechat",
                        UUID.randomUUID().toString());
        long attempt = Long.parseLong(payment.attempts().getFirst().id());
        payments.authorizeSandbox(user, attempt, PIN);
        var until = memberships.me(user).validUntil();
        assertThat(actual(memberships.me(user).active())).isTrue();
        payments.authorizeSandbox(user, attempt, PIN);
        assertThat(actual(memberships.me(user).validUntil())).isEqualTo(until);
        assertThat(actual(wallet.get(user).balance())).isEqualTo("801.00");
    }

    @Test
    @CaseSpec(
            input = "余额1000.00，用内部历史模拟入口支付200.10而非钱包扣款；退款200.10两次",
            expected = "历史付款退款成功；钱包余额仍1000.00；不伪造REFUND流水")
    void historicalMockRefundDoesNotMintWalletFunds() {
        long user = fundedUser();
        var payment = pending(user);
        var attempt = payment.attempts().getFirst();
        payments.simulate(user, Long.parseLong(attempt.id()));
        var command =
                new PaymentGateway.RefundCommand(
                        UUID.randomUUID().toString(),
                        attempt.outTradeNo(),
                        new java.math.BigDecimal("200.10"));
        assertThat(actual(gateway.refund(command))).isEqualTo(PaymentGateway.RefundState.SUCCEEDED);
        gateway.refund(command);
        assertThat(actual(wallet.get(user).balance())).isEqualTo("1000.00");
        assertThat(actual(wallet.entries(user, 0, 20).size())).isEqualTo(1);
    }
}
