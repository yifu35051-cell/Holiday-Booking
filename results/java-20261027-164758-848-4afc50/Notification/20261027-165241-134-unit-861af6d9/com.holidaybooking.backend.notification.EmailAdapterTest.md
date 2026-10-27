# Java 后端测试实际执行报告

开始时间（UTC）：2026-10-27T08:52:41.124552700Z

PASSED = 2；FAILED = 0；SKIPPED = 0；ABORTED = 0；SETUP_FAILED = 0；

实际值按测试断言顺序记录。PASSED 表示全部断言通过；SKIPPED/ABORTED 不是通过。数据库用例使用隔离 MySQL；模拟渠道不能替代真实支付或邮件服务验收。

## com.holidaybooking.backend.notification.EmailAdapterTest#localAndRestrictedModesDoNotSendMail

**输入：** 同一邮件任务分别采用log、disabled和smtp但收件人不在允许名单的配置

**预期：** 依次SIMULATED、FAILED、FAILED；均不调用SMTP发送器

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: SIMULATED
实际断言值: FAILED
实际断言值: FAILED
~~~

## com.holidaybooking.backend.notification.EmailAdapterTest#smtpOutcomesDistinguishAcceptanceRetryAndUncertainty

**输入：** SMTP发送器依次正常返回、抛认证失败、抛发送结果未知异常

**预期：** 依次SENT、RETRY、UNKNOWN；SENT只代表SMTP接受，不代表最终收件

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: SENT
实际断言值: RETRY
实际断言值: UNKNOWN
~~~

