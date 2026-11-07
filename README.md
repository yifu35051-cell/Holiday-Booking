# Holiday Booking

Vue 3 + Spring Boot + MySQL 的酒店预订系统，提供顾客、员工和管理员独立入口。当前运行方案为 **WireMock 本地支付沙箱 + DirectMail 真实 SMTP**，开发版与 Docker 部署版使用同一业务实现。支付不涉及真实资金，也不需要公网 IP；邮件会向真实邮箱投递。

| 目录 | 内容 |
| --- | --- |
| [backend](backend/README.md) | Java 后端、业务接口、数据库迁移与分模块测试 |
| [frontend](frontend/README.md) | Vue 三角色界面与运行方式 |
| config | 开发/部署配置、Compose、WireMock 映射；真实 `.env` 不提交 |
| scripts | 启动、测试、故障场景、备份与交付工具 |
| docs | [支付与邮件](docs/支付与邮件部署.md)、[测试入口](docs/testing/README.md)、[整改审查](docs/design/Implementation-Review.md) |
| results | 测试实际输出；历史结果不代表当前版本已验收 |

## 开发运行

首次配置 `config/development.env`；已有文件保留数据库密码。本次已按现有 DirectMail 配置更新渠道并生成本地回调密钥。首次准备或源码修改后，明确执行一次：

```powershell
# WireMock 镜像仅首次缺少时准备；MySQL 仍复用 mysql:8.4
docker pull wiremock/wiremock:3.13.2-2
.\backend\mvnw.cmd -f .\backend\pom.xml -DskipTests package
cd frontend
npm ci
cd ..
```

以后日常只启动，不自动构建或安装依赖：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-System.ps1 -Mode Development
```

访问 `http://localhost:5173`。前后端分别启动、首次管理员及数据初始化见[后端说明](backend/README.md)。模拟数据导入是显式准备步骤，日常启动不发送演示邮件。

## Docker 部署运行

首次配置 `config/deployment.env`：数据库密码、唯一管理员、DirectMail、两个不同的随机密钥。接收别人交付的镜像时先 `docker load --input images.tar`；不需要再次构建。

从源码交付或更新本次代码时，明确构建一次应用镜像：

```powershell
docker pull wiremock/wiremock:3.13.2-2
docker compose --env-file config/deployment.env -f config/compose.yaml -f config/compose.build.yaml build backend frontend
```

日常启动/停止始终使用已安装镜像：

```powershell
docker compose --env-file config/deployment.env -f config/compose.yaml up -d --no-build --pull never
docker compose --env-file config/deployment.env -f config/compose.yaml stop
```

访问 `http://localhost:8088`。MySQL、WireMock、后端不对宿主机发布端口；本机入口使用 HTTP，跨机器公开访问前需要 HTTPS。部署版保留本地支付沙箱，不能作为真实收款系统。

导出给成员：`powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Export-System.ps1`。包内包含镜像、Compose、WireMock 映射和配置示例，不包含真实凭据或工作数据库；成员初始化数据和恢复方式见[交付说明](docs/交付与演示数据.md)。

本轮编译与针对性单元检查已通过，Docker/MySQL 全链路尚未验收。完整结论见[逐项整改审查](docs/design/Implementation-Review.md)。
