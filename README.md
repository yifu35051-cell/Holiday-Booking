# Holiday Booking

本 README 说明当前后端的运行方式、完成情况和待办事项。更新日期：2026-10-27。

**当前状态：核心后端与本地模拟业务流程已实现。用户提供的 2026-10-27 单模块报告中，42 项测试全部通过，数据库测试使用 Docker MySQL；这次集成阶段 9 项尚无运行记录。支付宝沙箱和真实 SMTP 联调尚未完成。** 历史验证见第 4 节；当前测试与浏览器访问的区别见[运行指南第 7 节](docs/运行指南.md#7-测试启动登录与-api-文档)。

**当前开发路线：MySQL 在 Docker 容器中运行，暂不启用支付和邮件。** 请依次阅读：

首次编辑根目录 .env.mysql（参照 [.env.mysql.example](.env.mysql.example)）后，每次只执行：

~~~powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Backend.ps1
~~~

脚本读取文件配置，启动 MySQL 并等待健康检查，再启动 Java；普通启动不要求管理员邮箱或密码。只有显式追加 -InitializeAdmin 才使用文件中的管理员配置。只检查配置可追加 -CheckOnly。

1. [文档导航](docs/README.md)：统一入口及顾客、员工、管理员功能权限。
2. [运行指南](docs/运行指南.md)：Docker MySQL、文件配置、一键启动和排错。
3. [测试入口](docs/testing/README.md)：先单模块、再集成；每项列出功能、具体输入、预期与实际结果。实际结果待你运行后更新。

第 2 节提供日常启动步骤；详细安装及排错请查阅上述专用文档。

## 1. 项目结构与技术栈

~~~text
Holiday-Booking/
├── README.md                 当前运行说明与进度
├── compose.mysql.yaml        本地开发用 MySQL 容器
├── .env.mysql.example        数据库配置模板
├── scripts/Start-Backend.ps1  数据库与 Java 后端一键启动
├── scripts/Test-Backend.ps1   Java 测试模块选择、顺序执行和结果汇总
├── scripts/backend-tests/   非支付、非邮件 HTTP 测试脚本
├── results/                  每次 Java 测试的日志、逐类报告和汇总
├── backend/                  Spring Boot 后端
│   ├── pom.xml               依赖与构建配置
│   ├── mvnw.cmd              Windows Maven Wrapper
│   └── src/
│       ├── main/java/        业务代码
│       ├── main/resources/   应用配置、Flyway 数据库迁移
│       └── test/             自动化测试与测试配置
├── frontend/                 前端工程
└── docs/                     需求分析与架构设计资料
~~~

| 用途 | 当前技术 |
| --- | --- |
| 运行环境 | Java 21、Spring Boot 4.1.1、Maven Wrapper |
| HTTP 与校验 | Spring MVC、Bean Validation |
| 数据存储 | MySQL 8.4、Spring Data JPA、JdbcClient、Flyway |
| 认证与权限 | Spring Security、Spring Session JDBC、Session Cookie、CSRF |
| 外部服务 | 支付宝官方 Java SDK、Spring Mail；本地支持 mock 支付与 log 通知 |
| API 文档 | springdoc OpenAPI、Swagger UI |
| 测试与格式 | JUnit、Mockito、MySQL 8.4 Testcontainers、Spotless；纯单元测试无需数据库 |

后端为模块化单体。各业务模块内部按职责分层：api 处理 HTTP 和参数校验，application 编排用例与事务，domain 表达业务规则和仓储端口，infrastructure 实现数据库及外部服务访问。代码位于 [backend/src/main/java/com/holidaybooking/backend](backend/src/main/java/com/holidaybooking/backend)。

完整需求与设计见 [开发技术文档](docs/design/Holiday-Booking-开发技术文档.md)。该文档包含规划内容；**当前版本、实现状态和运行方式以本 README 及代码为准**。

## 2. 启动当前后端

### 2.1 首次配置

安装 JDK 21 和 Docker Desktop（Linux containers），在项目根目录执行：

~~~powershell
if (-not (Test-Path .\.env.mysql)) {
    Copy-Item .\.env.mysql.example .\.env.mysql
}
notepad .\.env.mysql
~~~

在文件中填写数据库密码和端口。管理员信息为可选项，普通启动忽略它们。已有配置时保留现有密码；修改文件不会重设已有数据卷中的数据库密码或已有管理员密码。

| 配置 | 作用 |
| --- | --- |
| MYSQL_ROOT_PASSWORD / MYSQL_PASSWORD | 数据库管理密码与应用连接密码 |
| MYSQL_PORT | 宿主机数据库端口，默认 3307；脚本自动同步 Java 连接地址 |
| SERVER_PORT | HTTP 端口，默认 8080 |
| BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD | 仅 -InitializeAdmin 时要求同时填写；普通启动忽略 |

管理员密码至少 12 字符，UTF-8 不超过 72 字节。详细格式及安装、初始化流程见 [Docker MySQL 本地开发流程](docs/运行指南.md)。

### 2.2 每次启动

所有命令从项目根目录执行：

~~~powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Backend.ps1
~~~

脚本读取 .env.mysql，检查或启动 Docker Desktop，启动 MySQL 容器并等待健康检查，然后前台启动 Spring Boot。首次运行需要下载镜像和 Maven 依赖。

本开发入口固定使用 local，禁用支付、邮件、相关定时任务和 SMTP 健康探测；数据库仍由 Flyway 自动迁移，Hibernate 校验结构。无需安装本地 MySQL，也无需手动设置 DB_* 环境变量。所有数据库测试也已改用隔离的 Docker MySQL，不连接开发数据库。

只检查配置及依赖工具、暂不启动服务：

~~~powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Backend.ps1 -CheckOnly
~~~

首次启动没有默认顾客、客房或预订，需要通过后台 API 建立房型和实体房。非支付业务测试脚本会创建自己所需的测试数据。

需要首次创建开发库管理员时，在 .env.mysql 填好两个 BOOTSTRAP_ADMIN_*，运行 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Backend.ps1 -InitializeAdmin`。普通启动和 Java 测试无需该配置；测试自动创建临时角色账户。

### 2.3 检查和停止服务

在另一终端检查，端口修改后相应调整：

~~~powershell
Invoke-RestMethod 'http://localhost:8080/actuator/health'
~~~

健康状态应为 UP。按 Ctrl+C 停止前台 Java；MySQL 容器和数据保留。需要停止数据库时，在 Java 退出后执行：

~~~powershell
docker compose --env-file .env.mysql -f compose.mysql.yaml stop
~~~

业务 API 前缀为 /api/v1，Swagger 位于 /swagger-ui/index.html，需要管理员会话。登录和写请求使用 Session Cookie + CSRF；详细操作见 [业务测试指南](scripts/backend-tests/README.md)。


## 3. 测试、格式检查与打包命令

以下命令同样在项目根目录执行。测试不要求先启动后端。

日常使用模块选择脚本，业务测试保留在 backend/src/test/java 的各模块目录中：

~~~powershell
# 交互菜单：支持全选、编号或多个模块标识
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Backend.ps1
# 暂缓邮件和支付时，运行核心六个模块（需要 Docker）
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Backend.ps1 -Modules Core
# 只运行账户和预订模块
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Backend.ps1 -Modules Identity,Booking
# 分别检查核心模块中的顾客、员工或管理员相关用例
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Backend.ps1 -Modules Core -Role Admin
# 全部顺序执行，包含 Docker MySQL；也可单独 -Modules MySql
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Backend.ps1 -Modules All
~~~

脚本逐模块启动独立 Maven/JVM；全选时先执行单模块检查，再执行跨模块和 MySQL 集成。也可用 -Stage Modules 或 -Stage Integrated 只选一个阶段。每个用例的输入、预期和实际观测值会打印到终端并保存到 results/java-时间戳/；[测试入口](docs/testing/README.md) 链接两份阶段用例表，执行后更新实际结果，未选模块保持“未执行”。历史报告保留。共45个普通 Java 用例及6个 MySQL 复用条目，最终结果待你执行，不能套用下方旧版验证记录。

以下 Maven 命令供单独构建使用；它们生成原始结果报告，但不会更新上述统一说明：

| 目的 | PowerShell 命令 | 环境要求 |
| --- | --- | --- |
| 运行模块测试 | .\backend\mvnw.cmd -f .\backend\pom.xml test | JDK21与Docker；数据库用例自动建立MySQL临时容器，不需外部服务凭据 |
| 完整构建与检查 | .\backend\mvnw.cmd -f .\backend\pom.xml verify | 模块测试、格式检查、打包和独立事务回归；Docker不可用时失败 |
| 检查 Java 格式 | .\backend\mvnw.cmd -f .\backend\pom.xml spotless:check | 不修改代码 |
| 自动整理 Java 格式 | .\backend\mvnw.cmd -f .\backend\pom.xml spotless:apply | 会修改 Java 文件格式 |

执行数据库测试前启动 Docker Desktop Linux 引擎；Testcontainers 创建随机端口、临时凭据的 MySQL 8.4 容器，首次需要下载镜像。不会回退到 H2，不读取 .env.mysql，不复用开发库。

构建可执行 JAR：

~~~powershell
.\backend\mvnw.cmd -f .\backend\pom.xml verify
~~~

构建产物为 backend/target/backend-0.0.1-SNAPSHOT.jar。日常开发使用第 2 节启动脚本；直接运行 JAR 不会自动加载 .env.mysql，部署运行参数需另行配置。

Maven 原生报告保存在 backend/target/surefire-reports 和 backend/target/failsafe-reports；逐类输入/预期/实际报告另存 results/。使用模块脚本时，原始 Maven 报告可能被下一模块覆盖，完整历史请看 results/。

## 4. 已完成的后端内容

### 4.1 业务与基础设施

| 模块 | 已实现内容 |
| --- | --- |
| identity：账户与权限 | 注册、登录、退出、当前账户；BCrypt 密码存储；CUSTOMER / STAFF / ADMIN 权限；管理员初始化、角色调整 |
| catalog：房源与库存 | 房型、实体房、基础房价和日期房价；逻辑停用；维护封房与恢复；同一实体房连续多晚可用性查询 |
| booking：报价与预订 | 服务端报价、逐晚价格和会员折扣快照；报价过期与一次消费；多房型原子下单、按晚占房、查询、归属校验、幂等及超时处理 |
| membership：会员 | 会员方案、购买单、支付后激活、续期、到期判断及折扣；注册账户不自动获得付费会员权益 |
| payment：支付 | 应付单与支付尝试分离；金额与方式校验；本地 Alipay / Wechat 模拟；查询、关单、重复通知处理、支付异常记录与人工复核 |
| notification：邮件任务 | 确认邮件及入住前一天 D1 提醒；任务持久化、租约、有限重试、发送配额与人工重试；log / SMTP 适配 |
| management：后台 API | 房型、房间、封房、预订、会员方案、角色、支付异常、通知任务及审计管理 |
| shared：工程基础 | 统一响应与异常、traceId、参数校验、分页、金额处理、配置校验、UTC 数据存储、Flyway 迁移、JDBC 会话 |
| 代码规范 | 分层目录、四空格缩进、合理空行、类职责与关键流程注释、EditorConfig 和 Spotless 格式检查 |

支付渠道结果未知时保留库存；核实未支付并完成关闭，或确认尚未创建支付尝试后，才按超时规则释放。人工复核支付异常只记录处理说明，尚不执行退款。

模拟邮件状态为 SIMULATED；SMTP 接受后记为 SENT，不代表已经到达收件箱。支付宝沙箱和 SMTP 的代码完成情况与真实联调状态分开记录，见第 5 节。



## 5. 待完成事项

### 5.1 当前运行必需的配置

| 项目 | 需要做什么 |
| --- | --- |
| JDK 21 与依赖下载 | 确认 java 和 Wrapper 可用 |
| MySQL | 在 .env.mysql 配置密码和端口，启动脚本自动建库及映射 JDBC 参数；真实迁移仍需环境验证 |
| 初始管理员（可选） | 普通启动不需要；需要管理开发库时配置两项并加 -InitializeAdmin |
| 业务数据 | 当前先验证查房、报价、创建待支付预订和库存；支付确认暂缓 |

依赖已在 [backend/pom.xml](backend/pom.xml) 声明。当前启动方案需要 Docker Desktop，无需 Redis、消息队列、真实支付或邮件服务。

### 5.2 已有代码，待配置和真实联调

| 项目 | 所需配置 | 尚需验证 |
| --- | --- | --- |
| MySQL 集成测试 | 可用 Docker 引擎，执行 verify | MySQL 8.4 下的迁移、并发锁、库存防重售与事务回滚 |
| 支付宝沙箱 | PAYMENT_MODE=alipay-sandbox；ALIPAY_APP_ID、ALIPAY_PRIVATE_KEY、ALIPAY_PUBLIC_KEY、ALIPAY_SELLER_ID、ALIPAY_NOTIFY_URL | 沙箱真实付款、签名回调、查询、关闭和超时恢复 |
| SMTP 邮件 | EMAIL_MODE=smtp；MAIL_HOST、MAIL_PORT、MAIL_USERNAME、MAIL_PASSWORD、MAIL_FROM 及 TLS 配置 | 实际收件、确认邮件、D1 提醒、服务商限额与失败处理 |

支付宝当前适配器限定沙箱。ALIPAY_PRIVATE_KEY 使用应用私钥的 PKCS8 Base64 正文，ALIPAY_PUBLIC_KEY 使用支付宝沙箱公钥。ALIPAY_NOTIFY_URL 应为渠道可访问的 HTTPS 地址，路径为 /api/v1/payment-callbacks/alipay。

SMTP 按服务商要求设置 MAIL_SMTP_AUTH、MAIL_STARTTLS、MAIL_SSL_ENABLE。例如 587 + STARTTLS 使用 MAIL_STARTTLS=true、MAIL_SSL_ENABLE=false；465 隐式 TLS 使用 MAIL_STARTTLS=false、MAIL_SSL_ENABLE=true。可用 MAIL_RECIPIENT_ALLOWLIST 限定测试收件人，EMAIL_DAILY_LIMIT 默认 50；具体配额仍以服务商为准。

### 5.3 尚未实现或尚未交付验收的工作

| 项目 | 待完成内容 |
| --- | --- |
| 真实微信支付 | 商户配置、API v3 密钥与证书、下单/查询/关闭、回调解密验签及渠道适配 |
| 生产支付宝 | 生产环境接入、正式商户配置、生产隔离与联调 |
| 自动退款与对账 | 退款单、幂等退款、查询恢复、账单核对、异常处理与告警 |
| 需求变更 CR1 / CR2 / CR3 | 取消预订、改订、D3 与 D1 提醒并存；目前保留设计，未实现变更接口 |
| 前端与端到端验收 | 用户及后台页面、Session / CSRF、同源代理、支付二维码、浏览器完整流程；不属于本轮后端已验收内容 |
| 其他账户功能 | 找回密码、邮箱验证、第三方登录，当前不包含这些能力 |
| 课程交付 | 项目团队完成真实用户验收、访谈记录和演示材料 |

### 5.4 可在部署阶段再完成

本地数据库 Compose 已提供。Java 应用 Dockerfile、完整应用 Compose、Nginx、HTTPS、生产 Secret 管理、Secure Cookie、数据库迁移与运行账户分离、备份恢复、监控告警和容量测试尚待配置与验证。

部署时需要显式选择运行 profile、支付和邮件模式；默认 local 使用 mock / log。使用 HTTPS 后配置 SESSION_COOKIE_SECURE=true，并按需要设置 API_DOCS_ENABLED。

## 6. 配置与资料入口

| 文件 | 用途 |
| --- | --- |
| [Docker MySQL 本地开发流程](docs/运行指南.md) | 无需安装本地 MySQL 的数据库启动方案 |
| [后端业务测试指南](scripts/backend-tests/README.md) | 非支付、非邮件模块测试命令和验收说明 |
| [Java 模块测试说明与实际输出](docs/testing/README.md) | 当前推荐测试入口；各用例输入、预期、待执行/实际结果 |
| [application.properties](backend/src/main/resources/application.properties) | 数据库、会话、支付、邮件和业务参数 |
| [application-local.properties](backend/src/main/resources/application-local.properties) | 本地 mock/log 默认值及可开关的 SMTP 健康探测；本轮通过环境变量禁用支付与邮件 |
| [application-test.properties](backend/src/test/resources/application-test.properties) | 隔离MySQL测试配置；实际连接由MySqlTestInitializer注入 |
| [数据库迁移目录](backend/src/main/resources/db/migration) | 业务表及 Spring Session 表结构 |
| [开发技术文档](docs/design/Holiday-Booking-开发技术文档.md) | 原始需求分析、整体架构与后续功能设计 |
| [设计资料目录](docs/design/README.md) | 离线阅读版、需求追踪表和测试计划入口，属于设计阶段资料 |

当前业务默认值：酒店时区 Asia/Shanghai，报价有效期 10 分钟，占房最长 15 分钟（不超过入住当天 15:00），最多提前 365 天、连续 30 晚、每单 10 间；D1 提醒在入住前一天 09:00。具体配置与业务校验以源码为准。
