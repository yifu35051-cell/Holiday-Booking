package com.holidaybooking.backend.payment.api;

import com.holidaybooking.backend.payment.application.PaymentService;
import com.holidaybooking.backend.shared.domain.DomainException;
import java.util.HashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/** 接收表单回调并拒绝重复参数；验签与业务核验由支付应用服务及渠道适配器完成。 */
@RestController
@RequestMapping("/api/v1/payment-callbacks")
@RequiredArgsConstructor
public class AlipayCallbackController {
    private final PaymentService payments;

    @PostMapping(
            value = "/alipay",
            consumes = "application/x-www-form-urlencoded",
            produces = "text/plain")
    public String callback(@RequestParam MultiValueMap<String, String> parameters) {
        var values = new HashMap<String, String>();
        parameters.forEach(
                (key, list) -> {
                    if (list.size() != 1) {
                        throw DomainException.invalid("DUPLICATE_PARAMETER", "回调参数不能重复");
                    }
                    values.put(key, list.getFirst());
                });
        payments.callback(values);
        return "success";
    }
}
