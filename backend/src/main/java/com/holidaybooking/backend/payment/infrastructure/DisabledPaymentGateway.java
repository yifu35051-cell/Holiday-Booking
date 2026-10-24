package com.holidaybooking.backend.payment.infrastructure;

import com.holidaybooking.backend.payment.domain.PaymentGateway;
import com.holidaybooking.backend.shared.domain.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 支付未配置时明确拒绝创建交易，防止返回虚假的支付成功。 */
@Component
@ConditionalOnProperty(
        name = "booking.payment-mode",
        havingValue = "disabled",
        matchIfMissing = true)
public class DisabledPaymentGateway implements PaymentGateway {
    public String provider() {
        return "DISABLED";
    }

    public Result create(Command command) {
        throw DomainException.unavailable("支付渠道尚未配置");
    }

    public Result query(Command command) {
        return new Result(command.outTradeNo(), State.UNKNOWN, null, null, null, null);
    }

    public Result close(Command command) {
        return query(command);
    }
}
