# 可选 HTTP 冒烟检查

日常模块测试统一使用 [Java 测试入口](../../docs/testing/README.md)。本目录的 `Run-BackendTests.ps1` 保留用于检查已经启动的开发服务，**会向开发库写入测试数据**，不使用 Java 测试的临时库。

先按[运行指南](../../docs/运行指南.md)启动后端。以下命令在项目根目录执行：

```powershell
# 只检查健康状态，无需管理员。
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\backend-tests\Run-BackendTests.ps1 -Module Health
# 检查所有已编排业务；需已有管理员，密码由脚本提示输入。
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\backend-tests\Run-BackendTests.ps1 -AdminEmail 'admin@example.com' -Module All
```

`-Module` 可选 Health、Identity、Catalog、Booking、Membership、Management、All，不包含邮件与支付流程。`-BaseUrl` 默认 `http://localhost:8080`；`-ReportDirectory` 默认 `backend/target/api-test-reports/`。脚本处理 Session Cookie 与 CSRF。

历史记录：2026-10-26 曾在临时 H2 服务完成 164 项 HTTP/业务检查；这不是当前 MySQL 版本的验收结果。当前版本应以重新执行后的报告为准。
