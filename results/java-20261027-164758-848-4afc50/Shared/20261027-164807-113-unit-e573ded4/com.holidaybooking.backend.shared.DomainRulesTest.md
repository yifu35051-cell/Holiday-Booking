# Java 后端测试实际执行报告

开始时间（UTC）：2026-10-27T08:48:07.104038100Z

PASSED = 2；FAILED = 0；SKIPPED = 0；ABORTED = 0；SETUP_FAILED = 0；

实际值按测试断言顺序记录。PASSED 表示全部断言通过；SKIPPED/ABORTED 不是通过。数据库用例使用隔离 MySQL；模拟渠道不能替代真实支付或邮件服务验收。

## com.holidaybooking.backend.shared.DomainRulesTest#checkoutIsExclusiveAndSameDayCutoffUsesResortZone

**输入：** 10月22日到24日；当地14:59:59和15:00尝试当日入住；零晚区间

**预期：** 占22/23两晚不含24；截止前接受，15:00和零晚区间拒绝

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: [2026-10-22, 2026-10-23]
实际异常: DomainException: 入住日期超出可预订范围
业务错误码: STAY_OUT_OF_RANGE
实际异常: DomainException: 离店日期必须晚于入住日期
业务错误码: INVALID_STAY
~~~

## com.holidaybooking.backend.shared.DomainRulesTest#roundsEachNightBeforeSumming

**输入：** 100.05乘0.9；非法金额1.001和-1

**预期：** 逐晚结果90.05；超精度及负数金额拒绝

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: 90.05
实际异常: IllegalArgumentException: 金额最多包含两位小数
实际异常: IllegalArgumentException: 金额超出有效范围
~~~

