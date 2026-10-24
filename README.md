# Holiday Booking

本 README 说明当前后端的运行方式、完成情况和待办事项。更新日期：2026-10-24。

**当前状态：核心后端与本地模拟业务流程已实现，已有 30 项测试通过记录；真实 MySQL、支付宝沙箱和 SMTP 的环境联调尚未完成。** 验证时间及边界见第 4 节。

## 1. 项目结构与技术栈

~~~text
Holiday-Booking/
├── README.md                 当前运行说明与进度
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
| 测试与格式 | JUnit、Mockito、H2、Testcontainers、Spotless |

后端为模块化单体。各业务模块内部按职责分层：api 处理 HTTP 和参数校验，application 编排用例与事务，domain 表达业务规则和仓储端口，infrastructure 实现数据库及外部服务访问。代码位于 [backend/src/main/java/com/holidaybooking/backend](backend/src/main/java/com/holidaybooking/backend)。

完整需求与设计见 [开发技术文档](docs/Holiday-Booking-开发技术文档.md)。该文档包含规划内容；**当前版本、实现状态和运行方式以本 README 及代码为准**。

## 2. 启动当前后端

### 2.1 环境准备

- 安装 JDK 21，确保 JAVA_HOME 指向 JDK 目录，java 命令可用。
- 准备可连接的 MySQL 8.4 实例，可以直接安装本地 MySQL。
- 首次使用 Maven Wrapper 需要网络下载 Maven 和项目依赖，无需另行安装 Maven。
- 本地运行使用模拟支付和日志通知，无需先配置 Docker、支付宝、微信或 SMTP。

**本文所有 PowerShell 命令均在项目根目录 Holiday-Booking 执行。** 命令通过 -f 指定后端 pom.xml，无需切换到 backend 目录。

~~~powershell
java -version
.\backend\mvnw.cmd -f .\backend\pom.xml -version
~~~

正常启动使用 MySQL；H2 仅供自动化测试，不会在 MySQL 连接失败时自动替代数据库。

### 2.2 首次准备数据库

在 MySQL 客户端使用有建库、建用户权限的账户执行以下 SQL，并替换示例密码。数据库和用户已存在时，使用已有配置即可，不要重复创建。

~~~sql
CREATE DATABASE holiday_booking
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'holiday_booking'@'localhost'
    IDENTIFIED BY 'REPLACE_WITH_LOCAL_DB_PASSWORD';

GRANT ALL PRIVILEGES ON holiday_booking.*
    TO 'holiday_booking'@'localhost';
~~~

这些是本地开发权限，包含 Flyway 建表权限。应用启动时自动执行数据库迁移，Hibernate 仅校验表结构，无需手动执行迁移 SQL。远程 MySQL 需按实际连接来源配置账户授权。

### 2.3 配置并启动

在项目根目录打开 PowerShell，替换密码后执行：

~~~powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:DB_URL = 'jdbc:mysql://localhost:3306/holiday_booking?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:DB_USERNAME = 'holiday_booking'
$env:DB_PASSWORD = 'REPLACE_WITH_LOCAL_DB_PASSWORD'

# 本地模拟模式：不发生真实付款或邮件发送。
$env:PAYMENT_MODE = 'mock'
$env:EMAIL_MODE = 'log'

# 首次启动时创建管理员；请替换为自己的邮箱和密码。
$env:BOOTSTRAP_ADMIN_EMAIL = 'admin@example.com'
$env:BOOTSTRAP_ADMIN_PASSWORD = 'REPLACE_WITH_YOUR_ADMIN_PASSWORD'

.\backend\mvnw.cmd -f .\backend\pom.xml spring-boot:run
~~~

没有默认管理员密码。密码要求 12–72 字符，且 UTF-8 编码不超过 72 字节。初始化不会将同邮箱的普通用户静默提升为管理员。

管理员创建后，后续启动可省略两个 BOOTSTRAP_ADMIN 变量，账户保留在数据库中。上述环境变量只在当前终端及其子进程中生效，新终端需重新设置必要配置。

首次启动只初始化演示会员方案（199.00 元、折扣系数 0.9000、期限 365 天），不自动创建房型、实体房或预订。管理员需要通过后台 API 建立房型和实体房，之后才能完成订房流程。会员参数是设计默认值，可通过方案管理调整。

### 2.4 检查服务与停止服务

默认端口为 8080。在另一终端执行：

~~~powershell
Invoke-RestMethod 'http://localhost:8080/actuator/health'
~~~

服务正常时返回 status 为 UP。停止前台服务使用 **Ctrl+C**。如需更换端口，在启动终端设置 $env:SERVER_PORT = '8081'，并相应调整访问地址。

| 入口 | 地址及访问要求 |
| --- | --- |
| 健康检查 | http://localhost:8080/actuator/health，公开访问 |
| 业务 API | http://localhost:8080/api/v1 |
| Swagger UI | http://localhost:8080/swagger-ui/index.html，需要管理员会话 |
| OpenAPI JSON | http://localhost:8080/v3/api-docs，需要管理员会话 |

认证采用 Session Cookie。调用登录、注册及其他写接口前，先 GET /api/v1/auth/csrf，保留 Cookie，并在 X-CSRF-TOKEN 请求头中携带返回的 data.token；登录成功后重新获取 token。浏览器和 PowerShell 的会话不互通，浏览器访问 Swagger 需要在同一浏览器会话中完成管理员登录。

创建预订、购买会员和创建支付尝试还要求 Idempotency-Key 请求头。详细参数可查看 OpenAPI 或各模块的 api 代码。

## 3. 测试、格式检查与打包命令

以下命令同样在项目根目录执行。测试不要求先启动后端。

| 目的 | PowerShell 命令 | 环境要求 |
| --- | --- | --- |
| 运行快速测试 | .\backend\mvnw.cmd -f .\backend\pom.xml test | JDK 21；使用 H2，无需 MySQL、Docker 或外部服务凭据 |
| 完整构建与检查 | .\backend\mvnw.cmd -f .\backend\pom.xml verify | 包含快速测试、格式检查、打包和 MySQL 集成测试；Docker 不可用时 MySQL 测试会跳过 |
| 检查 Java 格式 | .\backend\mvnw.cmd -f .\backend\pom.xml spotless:check | 不修改代码 |
| 自动整理 Java 格式 | .\backend\mvnw.cmd -f .\backend\pom.xml spotless:apply | 会修改 Java 文件格式 |

验证真实 MySQL 行为时，先启动可用的 Docker 引擎，再运行 verify；Testcontainers 会创建 MySQL 8.4 测试容器，首次需要下载镜像。**构建成功但集成测试被跳过，不代表 MySQL 验证完成。**

构建和运行可执行 JAR：

~~~powershell
.\backend\mvnw.cmd -f .\backend\pom.xml verify

# 构建成功后执行；需在此终端设置第 2.3 节的运行环境变量。
java -jar .\backend\target\backend-0.0.1-SNAPSHOT.jar
~~~

运行 JAR 与 spring-boot:run 是两种启动方式，选择一种即可，避免同时占用同一端口。

测试报告保存在 backend/target/surefire-reports 和 backend/target/failsafe-reports；JAR、日志及测试报告均属于构建产物。

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

### 4.2 最近一次已有验证记录

记录时间：**2026-10-22 16:59（Asia/Shanghai）**。本次 README 更新核对了现有测试报告，未重新运行测试。

| 验证项 | 已有结果 |
| --- | --- |
| Maven 构建与格式检查 | spotless:apply verify 构建成功；77 个生产及测试 Java 文件通过格式检查 |
| 快速测试 | 30 项通过，0 失败、0 错误、0 跳过 |
| MySQL 集成测试 | 10 项跳过，当次没有可用 Docker 引擎 |
| 可执行产物 | 已生成 backend/target/backend-0.0.1-SNAPSHOT.jar |

已有测试覆盖启动上下文、业务规则、预订和会员流程、并发及回滚、幂等、权限与 CSRF、支付恢复、支付宝本地验签、通知重试等。H2 测试不能替代真实 MySQL 锁行为、外部渠道调用或浏览器端验收。

## 5. 待完成事项

### 5.1 当前运行必需的配置

| 项目 | 需要做什么 |
| --- | --- |
| JDK 21 与依赖下载 | 确认 java 和 Wrapper 可用 |
| MySQL | 创建数据库、配置 DB_URL / DB_USERNAME / DB_PASSWORD，完成真实启动和迁移验证 |
| 初始管理员 | 首次启动配置 BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD |
| 业务数据 | 通过后台 API 创建房型、价格和实体房，再验证查房到支付确认流程 |

依赖已在 [backend/pom.xml](backend/pom.xml) 声明。当前本地运行无需先配置 Docker、Redis、消息队列、真实支付或邮件服务。

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

Dockerfile / Compose、Nginx、HTTPS、生产 Secret 管理、Secure Cookie、数据库迁移与运行账户分离、备份恢复、监控告警和容量测试尚待配置与验证。这些不是当前本地开发的前置条件。

部署时需要显式选择运行 profile、支付和邮件模式；默认 local 使用 mock / log。使用 HTTPS 后配置 SESSION_COOKIE_SECURE=true，并按需要设置 API_DOCS_ENABLED。

## 6. 配置与资料入口

| 文件 | 用途 |
| --- | --- |
| [application.properties](backend/src/main/resources/application.properties) | 数据库、会话、支付、邮件和业务参数 |
| [application-local.properties](backend/src/main/resources/application-local.properties) | 本地 mock 支付、log 通知默认值 |
| [application-test.properties](backend/src/test/resources/application-test.properties) | H2 测试环境与关闭定时调度的配置 |
| [数据库迁移目录](backend/src/main/resources/db/migration) | 业务表及 Spring Session 表结构 |
| [开发技术文档](docs/Holiday-Booking-开发技术文档.md) | 原始需求分析、整体架构与后续功能设计 |
| [设计资料目录](docs/README.md) | 离线阅读版、需求追踪表和测试计划入口，属于设计阶段资料 |

当前业务默认值：酒店时区 Asia/Shanghai，报价有效期 10 分钟，占房最长 15 分钟（不超过入住当天 15:00），最多提前 365 天、连续 30 晚、每单 10 间；D1 提醒在入住前一天 09:00。具体配置与业务校验以源码为准。
