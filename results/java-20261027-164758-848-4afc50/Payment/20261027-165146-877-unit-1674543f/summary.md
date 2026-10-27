# Java 后端测试实际执行报告

开始时间（UTC）：2026-10-27T08:51:46.867286500Z

PASSED = 12；FAILED = 0；SKIPPED = 0；ABORTED = 0；SETUP_FAILED = 0；

实际值按测试断言顺序记录。PASSED 表示全部断言通过；SKIPPED/ABORTED 不是通过。数据库用例使用隔离 MySQL；模拟渠道不能替代真实支付或邮件服务验收。

## com.holidaybooking.backend.payment.AlipaySignatureTest#validCallbackVerifiesAndParsesChinaTime

**输入：** 本地RSA2签名、金额100、商户正确、北京时间2026-10-22 08:00

**预期：** 结果PAID、金额100.00、时间2026-10-22T00:00:00Z

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: PAID
实际断言值: 100.00
实际断言值: 2026-10-22T00:00:00Z
~~~

## com.holidaybooking.backend.payment.AlipaySignatureTest#evenSignedCallbackMustBelongToConfiguredMerchant

**输入：** 错误seller_id但签名有效

**预期：** 拒绝不属于本商户的回调

**执行状态：** PASSED

**实际输出：**

~~~text
实际异常: DomainException: 支付回调验证失败
业务错误码: INVALID_SIGNATURE
~~~

## com.holidaybooking.backend.payment.AlipaySignatureTest#missingFieldsAndMalformedDatesAreControlledErrors

**输入：** 空回调；有效签名但付款时间not-a-date

**预期：** 两者均为可控DomainException，不确认支付

**执行状态：** PASSED

**实际输出：**

~~~text
实际异常: DomainException: 支付回调缺少必填参数
业务错误码: INVALID_CALLBACK
实际异常: DomainException: 支付回调参数或签名无效
业务错误码: INVALID_CALLBACK
~~~

## com.holidaybooking.backend.payment.AlipaySignatureTest#alteredAmountFailsSignatureVerification

**输入：** 签名后将100改为0.01但不重新签名

**预期：** 验签拒绝

**执行状态：** PASSED

**实际输出：**

~~~text
实际异常: DomainException: 支付回调验证失败
业务错误码: INVALID_SIGNATURE
~~~

## com.holidaybooking.backend.payment.PaymentFlowTest#paymentRetryIsIdempotentAndDifferentAttemptIsBlocked

**输入：** 同一支付尝试键重试，再尝试另一方式及错误金额

**预期：** 未决尝试仅1条；另一尝试被拒绝；错误金额抛异常

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: [AttemptView[id=1, outTradeNo=HB8688b64a5cfc4e958456141d74c87652, method=Alipay, provider=MOCK, status=PENDING, checkoutUrl=mock://HB8688b64a5cfc4e958456141d74c87652, paidAt=null]]
实际异常: DomainException: 已有待确认支付，请先查询其结果
业务错误码: PAYMENT_IN_PROGRESS
实际异常: PaymentAmountMismatchException: 支付金额与订单应付金额不一致
~~~

## com.holidaybooking.backend.payment.PaymentFlowTest#unpaidExpiryReleasesAllHeldNights

**输入：** 待付预订后推进时钟16分钟，执行未付单恢复

**预期：** 预订EXPIRED，占房夜数0

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: EXPIRED
实际断言值: 0
~~~

## com.holidaybooking.backend.payment.PaymentRecoveryTest#unknownExpiredPaymentKeepsInventoryUntilTheProviderConfirmsClosure

**输入：** 超过15分钟后渠道UNKNOWN；随后确认CLOSED

**预期：** 未知时保留占房，确认关闭后释放并EXPIRED

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: 2
实际断言值: PENDING
实际断言值: 0
实际断言值: EXPIRED
~~~

## com.holidaybooking.backend.payment.PaymentRecoveryTest#delayedVerifiedPaymentUsesTheActualPaidTimeAndIsIdempotent

**输入：** 真实支付时间在截止前，回调在16分钟后到达并重复通知

**预期：** 预订CONFIRMED；通知仅2条，不重复生成

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: CONFIRMED
实际断言值: 2
~~~

## com.holidaybooking.backend.payment.PaymentRecoveryTest#reviewRecordsAdministrativeDecisionWithoutConfirmingBooking

**输入：** 模拟收到0.01而应付200.00的回调，管理员查询异常并写入复核说明

**预期：** 异常记录可查询并记为REVIEWED；预订仍PENDING_PAYMENT；不发生退款或强制确认

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: true
实际断言值: REVIEWED
实际断言值: PENDING_PAYMENT
~~~

## com.holidaybooking.backend.payment.PaymentRecoveryTest#mismatchedIncomingFundsAreRecordedWithoutConfirmingBooking

**输入：** 应付200元的单收到签名渠道0.01元入账

**预期：** 预订PENDING_PAYMENT；记录AMOUNT_MISMATCH和实际金额0.01

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: PENDING_PAYMENT
实际断言值: AMOUNT_MISMATCH
实际断言值: 0.01
~~~

## com.holidaybooking.backend.payment.PaymentRecoveryTest#latePaymentCannotReopenAnExpiredBooking

**输入：** 先关闭过期单并释放库存，再收到迟到付款

**预期：** 预订仍EXPIRED，库存0，异常LATE_PAYMENT

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: EXPIRED
实际断言值: 0
实际断言值: LATE_PAYMENT
~~~

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

