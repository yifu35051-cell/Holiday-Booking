# Holiday Booking 后端

Java 21 / Spring Boot 4.1.1 / Maven Wrapper。数据库为MySQL 8.4，使用JPA、JdbcClient和Flyway；认证采用Spring Security、JDBC Session与CSRF。更新日期：2026-10-28。

## 运行

生产/部署版使用 `Dockerfile`、`../config/compose.yaml` 和整体启动/导出脚本，详细命令见[交付与演示数据](../docs/交付与演示数据.md)。项目根目录运行 `scripts/Start-System.ps1` 使用Docker栈；追加 `-Mode Development` 则沿用原开发数据库，并运行已有JAR和Vue热更新。两种启动入口均不自动下载或构建，首次准备与代码更新命令见项目根README。下面保留后端单独开发的命令。

以下命令均在**项目根目录**执行。安装JDK 21和Docker Desktop（Linux containers），首次将 `config/development.env.example` 复制为 `config/development.env` 并填写数据库密码。已有文件不要覆盖。完整安装及排错见[运行指南](../docs/运行指南.md)。

```powershell
# 首次或Java代码修改后显式打包；日常启动不执行此命令。
.\backend\mvnw.cmd -f .\backend\pom.xml -DskipTests package

# 只检查配置和工具，不启动服务
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Backend.ps1 -CheckOnly

# MySQL健康检查 → Mailpit SMTP检查（启用时）→ Java
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Backend.ps1
```

默认后端地址 `http://localhost:8080`，健康检查 `/actuator/health`。Ctrl+C停止Java，Docker服务及数据保留。Flyway自动应用V1至V5迁移；不要编辑已执行过的迁移或删除数据卷。

本机默认只监听127.0.0.1；Docker镜像通过SERVER_ADDRESS=0.0.0.0在容器内监听，Compose不直接发布Java端口。新增 `/actuator/health/readiness` 供整体启动等待，包括数据装载器执行完毕后的就绪状态。

| 配置 | 作用 |
| --- | --- |
| MYSQL_ROOT_PASSWORD / MYSQL_PASSWORD | 数据库管理密码与应用密码；改变文件不会重置已有数据库密码 |
| MYSQL_PORT / SERVER_PORT | 宿主机MySQL及HTTP端口，默认3307/8080 |
| PAYMENT_MODE=mock | 开发及部署版支付/退款模拟；无真实扣款，无需公网回调 |
| EMAIL_MODE=smtp / JOBS_ENABLED=true | 开启邮件投递及任务扫描 |
| MAILPIT_ENABLED=true | 启动或复用holiday-mailpit；默认SMTP1025、网页8025 |
| MAIL_HOST / MAIL_PORT / MAIL_FROM | 本地使用127.0.0.1、1025和系统发信地址 |
| MAIL_RECIPIENT_ALLOWLIST='' | 本地不限制为示例邮箱；个人发信仍要求已注册收件账户 |
| EMAIL_DAILY_LIMIT | 个人邮件与通知共用每日发送尝试预算，默认50 |
| BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD | 开发脚本仅在-InitializeAdmin时读取；Docker新库启动从部署配置读取 |

开发模板与Docker部署配置均启用mock和Mailpit；详细配置见[渠道部署指南](../docs/支付与邮件部署.md)。日常启动不需要外部支付或付费邮箱账号。不要将 `config/development.env` 的密码复制到前端。

### 管理员和员工

1. 如需初始管理员，在配置文件填写管理员邮箱及密码（至少12字符，UTF-8不超过72字节）。
2. 用同一命令追加 `-InitializeAdmin` 启动一次；之后恢复普通启动。不会重置已有管理员密码。
3. 顾客在前端注册。管理员在“账户与角色”页面将指定账户设为STAFF；该账户随后从员工入口登录。
4. 三角色只有CUSTOMER、STAFF、ADMIN，会员是付费权益，不是权限角色。

### 本地演示数据

`LocalDemoData`为显式启用的local启动装载器，不修改既有Flyway迁移、不提供HTTP入口。它复用Identity/Catalog/Booking/Membership/Payment/Mail业务服务，原子创建5个顾客/员工、4种房型/20间房、日期价/封房、2种方案、会员购买、4笔预订、mock支付/退款、4封待投递邮件及相关通知。原管理员保留；导入完成标记防止重复灌入。

```powershell
# 已运行开发MySQL时，只导入并退出；不占用8080，不启动投递任务。
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Backend.ps1 -SeedOnly -UseRunningDatabase

# 后端正常启动并按需首次导入（也可由Start-System -Mode Development自动调用）。
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Backend.ps1 -SeedDemoData
```

需要PAYMENT_MODE=mock且已有唯一管理员；新库先显式初始化管理员。Docker交付默认启用导入，Java默认关闭。演示数据日期、随机密码位置、重启保留及错误处理见交付指南。Windows本地凭据继承工作目录ACL；容器凭据文件为0600。不要发布真实配置或密码文件。

2026-10-28已向现有开发MySQL实际导入，并核验重复执行不重复插入、管理员仍为1个；四封个人演示邮件已进入SENT。具体数量与验证范围见交付指南末节。

## 分层与功能

每个业务模块按 `api → application → domain` 组织；`infrastructure`实现数据库和外部服务端口。应用层管理事务，网络投递在持久化租约提交后执行。

| 模块 | 已实现内容 |
| --- | --- |
| identity | 注册、登录、本人资料、退出、密码校验、会话及CSRF更新、管理员初始化、角色变更、管理员分页用户列表 |
| catalog | 公开房型详情、连续日期可用性；后台房型启停、实体房增建/启停、日期价格、封房与释放 |
| booking | 多房型报价、逐晚价格和权益快照、幂等下单、库存占用、本人/后台查询、超时处理 |
| booking changes | 提前至少24小时取消及改订、服务端差额报价、补款、库存差集、历史版本、本人分页变更历史 |
| payment | 应付单、支付尝试、离线钱包（密码设置/修改、模拟充值、锁定、授权扣款/资金流水）、查询刷新、结果未知恢复、取消/改订退款及钱包原路返还、异常收款复核；支付宝沙箱适配器保留 |
| membership | 方案、购买单、付款后激活、原方案续期、到期折扣、本人购买历史；有效会员即使原方案已停用仍可按原方案续期 |
| notification | 预订确认、D1/D3提醒、过期抑制、版本校验、通知任务管理及重试 |
| mail | 三角色个人收发、回复、读状态、未读数、回收站及恢复、管理员全站查看与审计；统一SMTP传输及退避策略 |
| management / shared | 员工及管理员API权限、审计、统一错误、分页、traceId、配置校验、金额与时间规则 |

notification负责通知发生的业务原因和时间；mail负责邮箱及通用邮件传输。仅有一个SMTP适配器和一份重试策略，两类任务共用发送配额。系统通知通过邮箱读模型展示，不复制排队重发。详细说明在[邮件指南](../docs/支付与邮件部署.md#41-每个账户自己的邮箱)。

## 前端对应与权限

| 角色 | 主要接口与能力 |
| --- | --- |
| CUSTOMER | `/room-types`、`/availability`、`/quotes`、`/bookings`、`/payment-orders`、`/refunds`、`/membership`、本人`/mail` |
| STAFF | `/admin/bookings`、`/admin/rooms`、`/admin/room-blocks`、房型只读、`/admin/notifications`、本人`/mail` |
| ADMIN | 员工后台能力，加房型/日期价、会员方案、用户角色、支付异常、审计、`/admin/mail`；同时保留本人邮箱 |

上表路径前缀均为 `/api/v1`。后端历史接口允许员工/管理员使用部分本人资源接口；本轮Vue门户按角色分离业务菜单，员工/管理员门户不提供顾客预订/会员购买入口。真正权限边界仍由后端检查，而不是菜单隐藏。

本轮为完整页面补齐：

- `POST /auth/login` 新增可选 `portalRole`。先认证密码，再比较数据库角色，匹配才创建登录会话；不匹配403 `PORTAL_ROLE_MISMATCH`。旧API客户端不传该字段仍兼容，不能通过字段升级角色。
- `GET /admin/users?page=0&size=20`：仅ADMIN，分页响应不含密码哈希。
- `GET /membership/purchases?page=0&size=20`：仅当前账户的购买记录；`/membership/me`新增原方案planId供续期。
- `GET /bookings/{id}/changes?page=0&size=20`：先核验订单归属，再返回变更和退款信息。

成功结构为 `{data, traceId}`；业务ID通常为字符串，金额遵循各接口既有DTO。写请求必须带Session Cookie及CSRF；幂等操作额外带Idempotency-Key。401代表未登录，403代表权限或CSRF失败，404隐藏不存在/不属于本人的资源，409为冲突，422为业务/字段校验失败，429为限流。

Swagger：`http://localhost:8080/swagger-ui/index.html`，OpenAPI：`/v3/api-docs`，均要求ADMIN且启用API_DOCS_ENABLED。浏览器前端使用Vite同源代理，后端无需放开跨域或关闭CSRF。端口、Cookie和登录排错见运行指南。

离线钱包仍属于payment模块，使用V5新增的sandbox_wallet/sandbox_wallet_entry表。支付密码BCrypt保存；错误5次锁定15分钟；旧无密码succeed接口已删除。首次使用需用项目登录密码开通钱包并模拟充值，既有预设已付款订单不追扣余额。接口规约及升级命令见[支付指南](../docs/支付与邮件部署.md#11-本次选择持久化离线钱包沙箱)。

## 测试与验证

```powershell
# 列出模块 / 只生成说明（不执行测试）
.\scripts\Test-Backend.ps1 -List
.\scripts\Test-Backend.ps1 -PrepareOnly

# 自行选择模块；结果写入results/java-时间戳/
.\scripts\Test-Backend.ps1 -Modules Identity,Management,Membership,Changes
.\scripts\Test-Backend.ps1 -Modules Mail,Notification
.\scripts\Test-Backend.ps1 -Modules SandboxPayment

# 单独向本地Mailpit发一封连通性测试邮件；All不含此模块
.\scripts\Test-Backend.ps1 -Modules MailpitSmoke -MavenOptions '-Dmailpit.smoke=true'

# 编译与格式检查，不执行测试
.\backend\mvnw.cmd -f .\backend\pom.xml spotless:check test-compile
```

持久化/MockMvc测试使用Testcontainers独立MySQL，不使用开发数据库或正在运行的8080服务。纯单元不需要MySQL；邮件业务测试使用SMTP替身，MailpitSmoke才连接容器。测试用例、输入、预期及实际结果统一在[两阶段测试表](../docs/testing/README.md)，不要将旧版本通过记录当作本轮验收。

本轮新增登录入口角色校验、管理员用户列表、会员购买归属、变更历史归属测试。按用户要求未预先执行测试；编译/静态检查已完成，不宣称实际数据库迁移或浏览器全流程验收通过。

## 尚待完成或部署验证

- 用户自行执行新增MySQL测试，核验本机Mailpit完整投递、三角色浏览器流程、重试与退款边界。
- 公网支付回调、支付宝沙箱/真实渠道联调、真实微信支付和生产退款对账；当前继续使用mock。
- 互联网SMTP、退信处理、入站邮件同步；当前开发及部署版使用Mailpit捕获邮件，应用邮箱不是通用互联网邮箱服务。
- 邮箱验证、找回密码、取消/改订专用通知模板、异常收款自动退款。
- 生产HTTPS、密钥管理、数据库备份及运行监控。已提供Java/Vue多阶段镜像及Nginx反向代理；生产/部署栈保留mock/Mailpit，完整镜像启动仍待实机验证；默认回环监听，跨机器发布需配置入口。
