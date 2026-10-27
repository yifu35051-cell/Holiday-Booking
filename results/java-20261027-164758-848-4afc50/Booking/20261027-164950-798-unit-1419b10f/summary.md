# Java 后端测试实际执行报告

开始时间（UTC）：2026-10-27T08:49:50.788495200Z

PASSED = 6；FAILED = 0；SKIPPED = 0；ABORTED = 0；SETUP_FAILED = 0；

实际值按测试断言顺序记录。PASSED 表示全部断言通过；SKIPPED/ABORTED 不是通过。数据库用例使用隔离 MySQL；模拟渠道不能替代真实支付或邮件服务验收。

## com.holidaybooking.backend.booking.BookingServiceTest#multiTypeFailureRollsBackConsumptionAndAnyInventoryChanges

**输入：** 两个房型报价后封住第二房型，再创建预订

**预期：** 整笔失败；报价consumed_at为空；第一房型仍可用

**执行状态：** PASSED

**实际输出：**

~~~text
实际异常: DomainException: 房间已被其他顾客预订
业务错误码: NO_AVAILABILITY
实际断言值: null
实际断言值: [Availability[roomType=TypeView[id=1, name=Garden 0927ef42-d548-401b-859b-6c0b9db688cb, description=Garden view, capacity=2, basePrice=100.05, active=true], availableRooms=1, nights=2, totalPerRoom=200.10]]
~~~

## com.holidaybooking.backend.booking.BookingServiceTest#ownershipAndOccupiedRoomProtectionApplyInServices

**输入：** 其他用户读取预订/应付单；尝试停用有未来占用的实体房

**预期：** 越权读取和停用操作均被拒绝

**执行状态：** PASSED

**实际输出：**

~~~text
实际异常: DomainException: 记录不存在或无权访问
业务错误码: NOT_FOUND
实际异常: DomainException: 记录不存在或无权访问
业务错误码: NOT_FOUND
实际异常: DomainException: 存在未来占用，不能停用房间
业务错误码: OCCUPIED
~~~

## com.holidaybooking.backend.booking.BookingServiceTest#competingCustomersCannotBothBookTheLastRoom

**输入：** 两个用户持有同一房型报价，同时提交最后1间房的2晚预订

**预期：** 并发结果恰好一个true一个false；库存表只有2个夜记录

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: [false, true]
实际断言值: 2
~~~

## com.holidaybooking.backend.booking.BookingServiceTest#pendingBookingPreservesQuoteAndSupportsFilteredQueries

**输入：** 100.05/晚、住2晚的报价；报价后改当前房价999；相同幂等键重复下单

**预期：** 报价和订单仍200.10；PENDING_PAYMENT；同键同ID；列表按用户/状态/日期筛选；非法分页拒绝

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: 1
实际断言值: 200.10
实际断言值: PENDING_PAYMENT
实际断言值: 3
实际断言值: 1
实际断言值: 0
实际异常: DomainException: page须非负，size须在1到100之间
业务错误码: INVALID_PAGINATION
实际异常: DomainException: 日期筛选范围无效
业务错误码: DATE_RANGE
~~~

## com.holidaybooking.backend.booking.BookingServiceTest#quoteLimitsAndOwnershipAreEnforced

**输入：** 重复房型选择、每间3人但容量2、请求11间以及他人消费本人的报价

**预期：** 非法选择/容量/数量返回业务异常；他人消费报价NOT_FOUND，报价仍可由本人成功下单

**执行状态：** PASSED

**实际输出：**

~~~text
实际异常: DomainException: 房型不能重复，间数必须为正数
业务错误码: SELECTION
实际异常: DomainException: 房型不可订或每间人数超出容量
业务错误码: CAPACITY
实际异常: DomainException: 房型不能重复，间数必须为正数
业务错误码: SELECTION
实际异常: DomainException: 记录不存在或无权访问
业务错误码: NOT_FOUND
实际断言值: PENDING_PAYMENT
~~~

## com.holidaybooking.backend.booking.BookingServiceTest#expiredQuoteAndChangedIdempotencyPayloadAreRejected

**输入：** 报价后推进11分钟；新报价下单后同键更改联系人

**预期：** 分别拒绝过期报价和不同内容复用幂等键

**执行状态：** PASSED

**实际输出：**

~~~text
实际异常: DomainException: 报价已过期，请重新报价
业务错误码: QUOTE_EXPIRED
实际异常: DomainException: 同一幂等键不能用于不同请求
业务错误码: IDEMPOTENCY_CONFLICT
~~~

