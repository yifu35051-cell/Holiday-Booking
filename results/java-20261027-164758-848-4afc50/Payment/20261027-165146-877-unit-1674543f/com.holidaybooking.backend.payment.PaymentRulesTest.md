# Java 后端测试实际执行报告

开始时间（UTC）：2026-10-27T08:51:46.867286500Z

PASSED = 1；FAILED = 0；SKIPPED = 0；ABORTED = 0；SETUP_FAILED = 0；

实际值按测试断言顺序记录。PASSED 表示全部断言通过；SKIPPED/ABORTED 不是通过。数据库用例使用隔离 MySQL；模拟渠道不能替代真实支付或邮件服务验收。

## com.holidaybooking.backend.payment.PaymentRulesTest#paymentMustMatchTrustedAmountAndExactMethod

**输入：** 合法Alipay/Wechat及非法大小写、cash、空方式；不匹配或非法金额

**预期：** 合法请求接受；非法方式UnsupportedPaymentMethodException；非法金额PaymentAmountMismatchException

**执行状态：** PASSED

**实际输出：**

~~~text
实际异常: UnsupportedPaymentMethodException: 支付方式只能是 Alipay 或 Wechat
实际异常: UnsupportedPaymentMethodException: 支付方式只能是 Alipay 或 Wechat
实际异常: UnsupportedPaymentMethodException: 支付方式只能是 Alipay 或 Wechat
实际异常: UnsupportedPaymentMethodException: 支付方式只能是 Alipay 或 Wechat
实际异常: UnsupportedPaymentMethodException: 支付方式只能是 Alipay 或 Wechat
实际异常: PaymentAmountMismatchException: 支付金额与订单应付金额不一致
实际异常: PaymentAmountMismatchException: 支付金额与订单应付金额不一致
实际异常: PaymentAmountMismatchException: 支付金额与订单应付金额不一致
实际异常: PaymentAmountMismatchException: 支付金额与订单应付金额不一致
~~~

