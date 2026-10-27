# Java 后端测试实际执行报告

开始时间（UTC）：2026-10-27T08:52:41.124552700Z

PASSED = 6；FAILED = 0；SKIPPED = 0；ABORTED = 0；SETUP_FAILED = 0；

实际值按测试断言顺序记录。PASSED 表示全部断言通过；SKIPPED/ABORTED 不是通过。数据库用例使用隔离 MySQL；模拟渠道不能替代真实支付或邮件服务验收。

## com.holidaybooking.backend.notification.NotificationDeliveryTest#retriesUseBoundedBackoffAndStopAfterFourAttempts

**输入：** 发送器连续RETRY；推进1、5、30分钟

**预期：** 立即重复调度不重发；总4次后FAILED

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: RETRY
实际断言值: FAILED
~~~

## com.holidaybooking.backend.notification.NotificationDeliveryTest#dailyQuotaDefersDeliveryWithoutConsumingAnAttempt

**输入：** 当天发送配额已使用50次，再调度一个待发送确认任务

**预期：** 任务保持READY且attempts=0；不调用发送器；后台列表可查该任务

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: READY
实际断言值: 0
~~~

## com.holidaybooking.backend.notification.NotificationDeliveryTest#reminderIsSkippedAfterArrivalInsteadOfSendingStaleMail

**输入：** 确认邮件已发送，时钟推进20天到入住之后

**预期：** D1为SKIPPED，不调用发送器

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: SKIPPED
~~~

## com.holidaybooking.backend.notification.NotificationDeliveryTest#expiredWorkerLeaseIsNotAutomaticallyResent

**输入：** 任务SENDING且租约已过期

**预期：** 转UNKNOWN，不调用发送器

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: UNKNOWN
~~~

## com.holidaybooking.backend.notification.NotificationDeliveryTest#sameDayArrivalSkipsPriorDayReminder

**输入：** 酒店入住当天08:00，为已存在的预订版本重新生成通知任务（先清理本用例任务）

**预期：** 确认任务READY，D1提醒SKIPPED，不补发前一天提醒

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: SKIPPED
实际断言值: READY
~~~

## com.holidaybooking.backend.notification.NotificationDeliveryTest#uncertainDeliveryRequiresExplicitAcknowledgementBeforeRetry

**输入：** 发送结果UNKNOWN，推进1小时，人工分别不确认/确认重试

**预期：** UNKNOWN不自动重发；未确认重试拒绝；确认重试后可SENT

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: UNKNOWN
实际异常: DomainException: 仅失败任务可重试；未知状态需要明确确认重复发送风险
业务错误码: NOT_RETRYABLE
实际断言值: SENT
~~~

