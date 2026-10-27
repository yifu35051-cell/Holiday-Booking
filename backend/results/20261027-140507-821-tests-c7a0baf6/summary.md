# Java 后端测试实际执行报告

开始时间（UTC）：2026-10-27T06:05:07.813761200Z

PASSED = 3；FAILED = 0；SKIPPED = 0；ABORTED = 0；SETUP_FAILED = 0；

实际值按测试断言顺序记录。PASSED 表示全部断言通过；SKIPPED/ABORTED 不是通过。H2 与模拟渠道不能替代真实外部服务验收。

## com.holidaybooking.backend.catalog.CatalogServiceTest#dailyFreeCountsDoNotImplyAContinuousAvailableRoom

**输入：** 2间实体房，在2晚中分别封住不同一晚

**预期：** 没有完整连续可住房；创建报价被拒绝

**执行状态：** PASSED

**实际输出：**

~~~text
实际断言值: []
实际异常: DomainException: 所选日期没有足够的连续可住房间
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
实际异常: DomainException: 人数或间数不合法
实际异常: DomainException: 人数或间数不合法
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
实际异常: DuplicateKeyException: PreparedStatementCallback; SQL [insert into room_night(room_id,stay_date,state,block_id) values(?,?,'BLOCKED',?)]; Unique index or primary key violation: "public.CONSTRAINT_4B INDEX public.PRIMARY_KEY_4B ON public.room_night(room_id, stay_date) VALUES ( /* key:3 */ CAST(4 AS BIGINT), DATE '2026-11-10')"; SQL statement:
insert into room_night(room_id,stay_date,state,block_id) values(?,?,'BLOCKED',?) [23505-240]
实际断言值: 2
实际断言值: false
实际断言值: 1
实际断言值: true
实际断言值: [RoomView[id=4, roomTypeId=3, number=e27ab58a-f09f-450e-a, active=true], RoomView[id=5, roomTypeId=3, number=02579cef-8dd6-4021-b, active=true]]
~~~

