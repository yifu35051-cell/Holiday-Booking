# Java 后端测试实际执行报告

开始时间（UTC）：2026-10-27T08:51:04.498899100Z

PASSED = 2；FAILED = 0；SKIPPED = 0；ABORTED = 0；SETUP_FAILED = 0；

实际值按测试断言顺序记录。PASSED 表示全部断言通过；SKIPPED/ABORTED 不是通过。数据库用例使用隔离 MySQL；模拟渠道不能替代真实支付或邮件服务验收。

## com.holidaybooking.backend.management.ManagementApiTest#administratorCanMaintainCatalogAndReadAuditWithBoundedPagination

**输入：** 具有数据库ADMIN角色的用户创建房型，再查询房型列表、审计和非法分页

**预期：** 创建200且价格100.00；后台查询200；审计有CREATE_TYPE；size=101返回422和INVALID_PAGINATION

**执行状态：** PASSED

**实际输出：**

~~~text
POST /api/v1/admin/room-types: HTTP 200 {"data":{"id":"1","name":"API Suite","description":"Test","capacity":2,"basePrice":"100.00","active":true},"traceId":"0385437c-1071-4e26-b3e7-ab03d4fdb98a"}
GET /api/v1/admin/room-types: HTTP 200 {"data":[{"id":"1","name":"API Suite","description":"Test","capacity":2,"basePrice":"100.00","active":true}],"traceId":"ae27e961-4613-4901-b147-17af82da7658"}
GET /api/v1/admin/audit-logs: HTTP 200 {"data":[{"id":"2","actorId":"1","action":"CREATE_TYPE","resourceType":"ROOM_TYPE","resourceId":"1","detail":"","createdAt":"2026-10-22T00:00:00Z"},{"id":"1","actorId":"2","action":"CHANGE_ROLE","resourceType":"USER","resourceId":"1","detail":"ADMIN","createdAt":"2026-10-22T00:00:00Z"}],"traceId":"44e588d8-0e7d-49aa-a557-720ba7e02426"}
GET /api/v1/admin/bookings: HTTP 422 {"code":"INVALID_PAGINATION","message":"page须非负，size须在1到100之间","fieldErrors":[],"traceId":"879a84c6-ae23-40d2-82b4-d7bf97637840"}
实际断言值: true
~~~

## com.holidaybooking.backend.management.ManagementApiTest#roleChangesImmediatelyAffectAuthorizationAndAreAudited

**输入：** 管理员将测试用户升为 STAFF，再降为 CUSTOMER；测试用户请求后台列表与管理员审计

**预期：** 员工可查后台预订但审计403；降级后旧认证访问后台403；记录本用户的CHANGE_ROLE审计；禁止改自己角色

**执行状态：** PASSED

**实际输出：**

~~~text
PUT /api/v1/admin/users/4/role: HTTP 200 {"data":{"id":"4","email":"b2d2809c-af9c-46b8-ae45-d0d4e199df41@example.com","displayName":"Test Guest","role":"STAFF"},"traceId":"4abcaa74-7a4c-4907-803e-c0fa571947c0"}
GET /api/v1/admin/bookings: HTTP 200 {"data":{"items":[],"page":0,"size":20,"total":0},"traceId":"610edef4-5dea-4d98-8daf-8ffd28dbb8cd"}
GET /api/v1/admin/audit-logs: HTTP 403 {"code":"FORBIDDEN","message":"无权执行此操作","fieldErrors":[],"traceId":"f2e81ea2-fa1a-4822-a7be-1a1644d6272c"}
GET /api/v1/admin/bookings: HTTP 403 {"code":"FORBIDDEN","message":"无权限或 CSRF 校验失败","traceId":"fb0600fc-5dac-47c8-9b98-254fad943e88"}
实际断言值: 2
实际异常: DomainException: 不能修改自己的角色
业务错误码: SELF_ROLE_CHANGE
~~~

