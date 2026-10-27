# Java 后端测试实际执行报告

开始时间（UTC）：2026-10-27T08:49:21.315614200Z

PASSED = 3；FAILED = 0；SKIPPED = 0；ABORTED = 0；SETUP_FAILED = 0；

实际值按测试断言顺序记录。PASSED 表示全部断言通过；SKIPPED/ABORTED 不是通过。数据库用例使用隔离 MySQL；模拟渠道不能替代真实支付或邮件服务验收。

## com.holidaybooking.backend.catalog.CatalogServiceTest#dailyFreeCountsDoNotImplyAContinuousAvailableRoom

**输入：** 2间实体房，在2晚中分别封住不同一晚

**预期：** 没有完整连续可住房；创建报价被拒绝

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: []
实际异常: DomainException: 所选日期没有足够的连续可住房间
业务错误码: NO_AVAILABILITY
~~~

## com.holidaybooking.backend.catalog.CatalogServiceTest#inactiveTypesAreHiddenAndPartyLimitsAreValidated

**输入：** 创建房型后停用；分别调用公开列表、后台列表和详情

**预期：** 公开列表不显示，后台保留，详情NOT_FOUND；非法人数和房间数被拒绝

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: false
实际断言值: true
实际异常: DomainException: 记录不存在或无权访问
业务错误码: NOT_FOUND
实际异常: DomainException: 人数或间数不合法
业务错误码: INVALID_PARTY
实际异常: DomainException: 人数或间数不合法
业务错误码: INVALID_PARTY
~~~

## com.holidaybooking.backend.catalog.CatalogServiceTest#ratesBlocksAndRoomLifecycleUpdateAvailability

**输入：** 2间房，基础价改220，首晚覆盖300，住2晚；随后封房、解除、停用和启用实体房

**预期：** 总价520.00；库存2→1→2→1→2；封房历史保留；重复封房冲突

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: 520.00
实际断言值: 2
实际断言值: [{id=3, start_date=2026-11-10, end_date=2026-11-12, reason=Maintenance, released=false}]
实际断言值: 1
实际异常: DuplicateKeyException: PreparedStatementCallback; SQL [insert into room_night(room_id,stay_date,state,block_id) values(?,?,'BLOCKED',?)]; Duplicate entry '4-2026-11-10' for key 'room_night.PRIMARY'
实际断言值: 2
实际断言值: false
实际断言值: 1
实际断言值: true
实际断言值: [RoomView[id=4, roomTypeId=3, number=6a91ad38-1e72-4dda-9, active=true], RoomView[id=5, roomTypeId=3, number=8afe70fc-f9dd-4c64-a, active=true]]
实际断言值: 2
~~~

