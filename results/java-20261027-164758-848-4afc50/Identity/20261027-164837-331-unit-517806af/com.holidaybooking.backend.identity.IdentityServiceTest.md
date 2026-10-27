# Java 后端测试实际执行报告

开始时间（UTC）：2026-10-27T08:48:37.322540400Z

PASSED = 2；FAILED = 0；SKIPPED = 0；ABORTED = 0；SETUP_FAILED = 0；

实际值按测试断言顺序记录。PASSED 表示全部断言通过；SKIPPED/ABORTED 不是通过。数据库用例使用隔离 MySQL；模拟渠道不能替代真实支付或邮件服务验收。

## com.holidaybooking.backend.identity.IdentityServiceTest#registrationNormalizesEmailHashesPasswordAndRejectsInvalidAccounts

**输入：** 大小写混合邮箱、合法测试密码、重复注册和短密码

**预期：** 邮箱归一化、角色 CUSTOMER、密码为 BCrypt 且可匹配；重复邮箱和短密码被拒绝

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: guest-d509a511-4266-4e11-bfa5-5f8a92cb7f4f@example.com
实际断言值: CUSTOMER
实际断言值: true
实际断言值: true
实际异常: DomainException: 该邮箱已注册
业务错误码: EMAIL_EXISTS
实际异常: DomainException: 密码需为 12–72 字符且 UTF-8 编码不超过 72 字节
业务错误码: PASSWORD_LENGTH
~~~

## com.holidaybooking.backend.identity.IdentityServiceTest#administratorBootstrapIsExplicitAndDoesNotPromoteExistingCustomers

**输入：** 新的 bootstrap 邮箱和合法密码，连续初始化两次，再将邮箱设为现有普通用户

**预期：** 仅创建一个 ADMIN；重复执行不重设密码；拒绝将已有普通账户提升为管理员

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: ADMIN
实际断言值: true
实际断言值: 1
实际异常: IllegalStateException: Bootstrap email already belongs to a non-admin account
实际断言值: CUSTOMER
~~~

