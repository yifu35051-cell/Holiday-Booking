# Holiday Booking Backend

Java 21 / Spring Boot 4.1.1 的模块化单体。开发和部署均使用 MySQL 8.4；支付目标是 WireMock 本地渠道模拟，邮件目标是已配置的 DirectMail。保留 `mock` 仅供隔离测试和演示数据导入，不再作为正常启动的默认支付渠道。

## 功能与角色

| 角色 | 后端功能 |
| --- | --- |
| 游客 | 房型详情、连续日期可用性、会员方案、顾客注册与分角色登录 |
| CUSTOMER | 报价、原子占房、个人预订、取消/改订与差价结算；支付尝试、钱包 PIN/充值/流水、查询/取消付款、退款查询；会员购买/续期；个人邮箱 |
| STAFF | 房间新增/停用、封房/释放、所有预订筛选与详情、通知核验/重试；个人邮箱。不能调用顾客业务接口，也不能修改价格/角色 |
| ADMIN | 员工能力、房型/日期价、会员方案、角色管理、全部邮件审计、支付异常复核、恢复预算人工释放、操作审计与受保护指标；个人邮箱 |

会员是顾客权益，不是第四种登录角色。发信账号是酒店统一 SMTP 身份，各人的站内邮箱、权限和操作归属仍分别保存。管理员查看私人邮件的行为会审计。

模块按 `api → application → domain` 组织，`infrastructure` 实现领域端口；`shared/config` 负责装配。数据库现有 Flyway V1–V6，禁止修改已经执行的迁移来升级工作库。设计细节见[当前基线](../docs/design/Current-Design-Baseline.md)。

## 开发启动

在项目根目录执行。首次复制 `config/development.env.example` 为 `config/development.env`；已有文件保留数据库密码和 SMTP 密码。普通启动不会要求填写管理员凭据。

```powershell
# 首次或后端源码修改后构建一次；日常启动不做这一步
.\backend\mvnw.cmd -f .\backend\pom.xml -DskipTests package
# 镜像不存在时首次准备；已安装的同名镜像直接复用
docker pull mysql:8.4
docker pull wiremock/wiremock:3.13.2-2

# 日常：MySQL + WireMock + Java
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Backend.ps1
# 或前后端一起启动
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-System.ps1 -Mode Development
```

Java 使用已构建 JAR。源码修改后需要主动重新 package，再重新启动；脚本不会静默重建。后端 `http://127.0.0.1:8080`，健康检查 `/actuator/health/readiness`。Swagger `/swagger-ui/index.html` 需要 ADMIN 会话且 `API_DOCS_ENABLED=true`。业务接口统一前缀 `/api/v1`；直接在浏览器访问私人接口显示“请先登录”是正常鉴权结果。

第一次空数据库需要管理员时，在配置文件填写管理员邮箱/密码并执行一次：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Backend.ps1 -InitializeAdmin
```

新库需要模拟业务数据时，先停止普通后端，再单独导入并启动：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Backend.ps1 -SeedOnly
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Backend.ps1
```

导入进程临时使用 `mock/log` 且关闭任务，不发送真实邮件；保留唯一管理员，生成顾客/员工、4 种房型、20 间房、方案、预订等，重复导入不覆盖业务数据。随机演示密码保存在 `.local/demo-accounts.properties`，不要交付该私密文件。示例保留域名不会经过 DirectMail 投递。

## 必需配置

| 配置 | 说明 |
| --- | --- |
| MYSQL_PASSWORD / MYSQL_ROOT_PASSWORD | 必须与既有数据卷一致；开发宿主机端口默认 3307 |
| PAYMENT_MODE=wiremock | 开发 `local`、部署 `sandbox` profile 支持本地支付沙箱 |
| WIREMOCK_BASE_URL | 开发 `http://127.0.0.1:9090`；Docker 中为 `http://wiremock:8080` |
| WIREMOCK_CALLBACK_SECRET | 至少 32 字符随机密钥；只保存在后端配置 |
| EMAIL_MODE=smtp | 个人邮件和系统通知共同使用 DirectMail，不再依赖 Mailpit |
| MAIL_HOST / PORT / USERNAME / PASSWORD / FROM | 当前 SMTP 域名、465、已验证发信地址和 SMTP 专用密码；SSL=true、STARTTLS=false |
| JOBS_ENABLED=true | 自动邮件、支付恢复、退款和渠道回调调度 |
| EMAIL_DAILY_LIMIT | 应用预算，不等于阿里云账户实际额度；个人邮件最多使用约 80%，系统通知可使用剩余预算 |
| TRUSTED_EDGE_SECRET | Docker Nginx 与后端共享，防止任意伪造来源 IP；与支付回调密钥不同 |

远程 SMTP 必须使用 TLS；认证/准备失败停止自动重试；连接未建立按约定退避；投递不确定进入 UNKNOWN，由人工确认后重试。真实发件成功仅代表 SMTP 接受，不代表已到收件箱。详细流程、签名和故障命令见[支付与邮件指南](../docs/支付与邮件部署.md)。

Docker 部署启动与一次性镜像构建见[根 README](../README.md)。本轮新增迁移和前后端变化需要重新打包一次；旧镜像不会自动包含新代码。

## 测试与结果

```powershell
# 只生成用例说明；不执行测试
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Backend.ps1 -PrepareOnly
# 按模块选择
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Backend.ps1 -Modules PaymentProtocol
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Backend.ps1 -Modules Identity,Booking,SandboxPayment,Changes,Mail,Notification
```

所有数据库测试使用隔离 MySQL，测试不连接工作库；纯规则、签名和 HTTP 契约测试不需要 MySQL。Spring 测试阶段启动自己的应用上下文，与已经运行的 8080 服务分离，属于正常测试隔离。测试没有默认真实发信，DirectMail 实际检查单独运行 `python scripts/Test-DirectMail.py`；发送邮件须显式加收件人参数。

用例按单模块→集成排列，具体输入、预期及用户执行后的实际输出见[测试入口](../docs/testing/README.md)。本轮已编译全部测试源码并通过 8 项针对性检查，记录在 `results/design-improvements-unit/`；其余用例和 Docker E2E 没有据此宣称通过。

## 尚待验收/完善

WireMock 实机回调/故障/重启与 MySQL 资金一致性、三角色真实收件、DirectMail 退信反馈与地址抑制、性能负载、键盘和 200% 缩放、隔离备份恢复演练尚未完成。已有备份/保留期预览工具，未启用自动删除；HTTPS、镜像摘要冻结及发布证据仍需交付环境配置。逐项状态及 Appendix 2 成品门槛见[审查文档](../docs/design/Implementation-Review.md)。
