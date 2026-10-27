# Java 后端测试实际执行报告

开始时间（UTC）：2026-10-27T08:51:46.867286500Z

PASSED = 4；FAILED = 0；SKIPPED = 0；ABORTED = 0；SETUP_FAILED = 0；

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

