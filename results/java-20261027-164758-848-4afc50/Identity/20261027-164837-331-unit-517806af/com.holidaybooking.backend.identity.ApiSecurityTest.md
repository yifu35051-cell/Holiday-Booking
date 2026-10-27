# Java 后端测试实际执行报告

开始时间（UTC）：2026-10-27T08:48:37.322540400Z

PASSED = 4；FAILED = 0；SKIPPED = 0；ABORTED = 0；SETUP_FAILED = 0；

实际值按测试断言顺序记录。PASSED 表示全部断言通过；SKIPPED/ABORTED 不是通过。数据库用例使用隔离 MySQL；模拟渠道不能替代真实支付或邮件服务验收。

## com.holidaybooking.backend.identity.ApiSecurityTest#staffCanReadBookingsButCannotChangePrices

**输入：** 数据库角色STAFF查询后台预订并尝试创建房型

**预期：** 查询200，创建房型403

**执行状态：** PASSED

**实际输出：**

~~~text
GET /api/v1/admin/bookings: HTTP 200 {"data":{"items":[],"page":0,"size":20,"total":0},"traceId":"d726ab63-7937-4e08-87cb-35ad1f724cc7"}
POST /api/v1/admin/room-types: HTTP 403 {"code":"FORBIDDEN","message":"无权执行此操作","fieldErrors":[],"traceId":"1055685a-d799-4090-9050-83bfe98af29c"}
~~~

## com.holidaybooking.backend.identity.ApiSecurityTest#anonymousAndCustomerRequestsCannotReachManagement

**输入：** 匿名访问后台；数据库CUSTOMER携带旧ADMIN认证；注册缺CSRF

**预期：** 依次401、403、403

**执行状态：** PASSED

**实际输出：**

~~~text
GET /api/v1/admin/bookings: HTTP 401 {"code":"UNAUTHENTICATED","message":"请先登录","traceId":"b3b80863-6d2b-41e8-b748-01172c944997"}
GET /api/v1/admin/bookings: HTTP 403 {"code":"FORBIDDEN","message":"无权限或 CSRF 校验失败","traceId":"7bae78d1-8c43-4320-8405-4f09956d1b5b"}
POST /api/v1/auth/register: HTTP 403 {"code":"FORBIDDEN","message":"无权限或 CSRF 校验失败","traceId":"445a9ca3-1280-4982-b70b-dd68d7c4d547"}
~~~

## com.holidaybooking.backend.identity.ApiSecurityTest#loginRotatesSessionAndCsrfThenLogoutInvalidatesAuthentication

**输入：** 获取匿名CSRF→密码登录→用旧token退出→刷新token退出→查询本人

**预期：** 登录200且会话变化；本人CUSTOMER；旧token403；正确退出204；退出后401

**执行状态：** PASSED

**实际输出：**

~~~text
GET /api/v1/auth/csrf: HTTP 200 {"data":{"headerName":"X-CSRF-TOKEN","token":"<hidden>"},"traceId":"5f920435-17ca-4ea9-8d81-99c1dd43174b"}
实际断言值: Cookie 已返回（值隐藏）
POST /api/v1/auth/login: HTTP 200 {"data":{"id":"3","email":"58ec227d-7cee-4e9a-959b-191d659c7682@example.com","displayName":"Session Guest","role":"CUSTOMER"},"traceId":"6d0e835f-ed67-4ce6-a35e-d1d22de6b988"}
实际断言值: Cookie 已返回（值隐藏）
实际断言值: true
GET /api/v1/auth/me: HTTP 200 {"data":{"id":"3","email":"58ec227d-7cee-4e9a-959b-191d659c7682@example.com","displayName":"Session Guest","role":"CUSTOMER"},"traceId":"2f8a4b6a-7f8a-4f6f-bef0-5dd601e0459b"}
POST /api/v1/auth/logout: HTTP 403 {"code":"FORBIDDEN","message":"无权限或 CSRF 校验失败","traceId":"c556324a-9e76-461e-86cb-f12066fe3719"}
GET /api/v1/auth/csrf: HTTP 200 {"data":{"headerName":"X-CSRF-TOKEN","token":"<hidden>"},"traceId":"1eb77de8-3095-439b-bcaa-5431b8ef08d3"}
POST /api/v1/auth/logout: HTTP 204 
GET /api/v1/auth/me: HTTP 401 {"code":"UNAUTHENTICATED","message":"请先登录","traceId":"fddd555b-b570-48c6-9a32-b082eb6ac830"}
~~~

## com.holidaybooking.backend.identity.ApiSecurityTest#invalidCredentialsAndMalformedInputHaveStableErrors

**输入：** 错误密码登录；checkIn为not-a-date

**预期：** 登录401/AUTHENTICATION_FAILED；日期格式400且包含trace头

**执行状态：** PASSED

**实际输出：**

~~~text
POST /api/v1/auth/login: HTTP 401 {"code":"AUTHENTICATION_FAILED","message":"邮箱或密码错误，或账号不可用","fieldErrors":[],"traceId":"701f13c7-d08f-4926-a633-e74961ae5124"}
GET /api/v1/availability: HTTP 400 {"code":"INVALID_REQUEST","message":"请求格式或必填参数错误","fieldErrors":[],"traceId":"e80a01a5-6368-49e6-8f76-ccf9bfc7fd80"}
~~~

