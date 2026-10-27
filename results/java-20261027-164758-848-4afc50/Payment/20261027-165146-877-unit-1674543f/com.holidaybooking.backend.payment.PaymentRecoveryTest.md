# Java 后端测试实际执行报告

开始时间（UTC）：2026-10-27T08:51:46.867286500Z

PASSED = 5；FAILED = 0；SKIPPED = 0；ABORTED = 0；SETUP_FAILED = 0；

实际值按测试断言顺序记录。PASSED 表示全部断言通过；SKIPPED/ABORTED 不是通过。数据库用例使用隔离 MySQL；模拟渠道不能替代真实支付或邮件服务验收。

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

