# Java 后端测试实际执行报告

开始时间（UTC）：2026-10-27T08:51:46.867286500Z

PASSED = 2；FAILED = 0；SKIPPED = 0；ABORTED = 0；SETUP_FAILED = 0；

实际值按测试断言顺序记录。PASSED 表示全部断言通过；SKIPPED/ABORTED 不是通过。数据库用例使用隔离 MySQL；模拟渠道不能替代真实支付或邮件服务验收。

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

