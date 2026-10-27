# Java 后端测试实际执行报告

开始时间（UTC）：2026-10-27T08:48:07.104038100Z

PASSED = 1；FAILED = 0；SKIPPED = 0；ABORTED = 0；SETUP_FAILED = 0；

实际值按测试断言顺序记录。PASSED 表示全部断言通过；SKIPPED/ABORTED 不是通过。数据库用例使用隔离 MySQL；模拟渠道不能替代真实支付或邮件服务验收。

## com.holidaybooking.backend.shared.BackendApplicationTests#contextLoads

**输入：** 启动test profile，执行Flyway并装配Spring容器

**预期：** 数据源和预订服务Bean存在，应用上下文成功启动

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: true
实际断言值: true
~~~

