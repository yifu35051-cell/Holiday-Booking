# Holiday Booking · 假日旅居

Vue 3 + Spring Boot + MySQL 度假预订系统，提供顾客、员工、管理员独立门户。**开发版和生产/部署版均保留 mock 支付与 Mailpit 邮箱功能**：mock 模拟付款和退款，Mailpit 接收应用邮件，三角色在应用内使用各自邮箱，管理员可查看全站邮件。

## 1. 选择运行方式

所有命令在项目根目录执行；接收镜像包的成员在交付目录执行。

| | 生产/部署版 | 开发版 |
| --- | --- | --- |
| 运行方式 | Docker：Nginx + 后端 + MySQL + Mailpit | 本机 Java + Vue 热更新，Docker 提供 MySQL/Mailpit |
| 配置 | `config/deployment.env` | `config/development.env` |
| 页面 / Mailpit 运维页 | localhost:8088 / localhost:8026 | localhost:5173 / localhost:8025 |
| 必需工具 | Docker Desktop，Linux containers，Compose v2 | 另需 JDK 21、Node（版本见 frontend/package.json） |

原 `.env.mysql`、`.env.docker` 已迁至上述文件，内容保留；**已有配置不要用模板覆盖**。两个运行栈使用独立数据库，切换模式不会共享新增记录。配置清单见 [config

`local-v1`只是**两个应用镜像的版本标签**，不是一份包含MySQL/Mailpit的重复大安装包。`holiday-booking-demo-*`是部署容器/数据卷名称，不是另装了一套镜像。开发与部署共用镜像，但保留独立数据，避免开发修改影响部署记录。

如果报错 `No such image: holiday-booking-backend:local-v1`，说明运行的是部署配置且缺少该版本的项目后端镜像；已经安装MySQL/Mailpit并不能替代项目代码。可用 `docker image ls` 检查；若已有两个应用镜像的其他匹配版本，把 `config/deployment.env` 的 `APP_VERSION` 改为对应标签，无需重建。复用要求同一个Docker引擎、准确的镜像名和标签；不会自动拿其他MySQL版本替代8.4。

## 2. 生产/部署版：日常直接运行

本机已有镜像和配置时，只需：

```sh
docker compose --env-file config/deployment.env -f config/compose.yaml up -d --no-build --pull never --wait --wait-timeout 300
```

打开 **http://localhost:8088**。也可执行等价入口：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-System.ps1 -Mode Docker
```

**这两个入口均不构建、不拉取镜像、不安装依赖，也不要求先导出镜像。** 缺少镜像时会报错，请完成下方首次准备。已有服务不会因重复执行命令而无条件重建；配置或镜像发生变化时，Compose 才按需更新容器。

PowerShell入口会先逐项检查镜像，显示复用/缺少清单；全部具备才调用Compose启动。直接运行Docker命令不经过此预检查，可能先创建网络、数据卷或部分容器，再报告缺少镜像。已有这些资源无需删除，补齐镜像后继续启动即可。

```sh
# 查看状态、日志、停止（保留数据）
docker compose --env-file config/deployment.env -f config/compose.yaml ps
docker compose --env-file config/deployment.env -f config/compose.yaml logs --tail 100 backend
docker compose --env-file config/deployment.env -f config/compose.yaml stop
```

### 首次准备：从源码制作镜像

只在没有配置/镜像时执行。若收到镜像包，直接使用下一节的导入流程。

```powershell
if (-not (Test-Path config/deployment.env)) {
    Copy-Item config/deployment.env.example config/deployment.env
}
notepad config/deployment.env
# 填写两项数据库密码及首次管理员邮箱/密码，保留已有数据库密码。

# 仅准备缺少的镜像：已有的全部跳过；不创建或启动容器。
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-System.ps1 -Mode Docker -PrepareImages
```

准备操作逐项检查：只有MySQL/Mailpit镜像缺少才拉取；只有项目后端/前端镜像缺少才构建对应服务。重复执行且镜像齐全时不拉取、不构建。首次应用构建可能下载Java/Node等构建基础镜像及依赖，它们与MySQL/Mailpit用途不同。完成后使用上面的日常启动命令。首次启动自动迁移数据库并装载房型、客房、顾客/员工、会员、订单和邮件等预设数据，保留唯一管理员。账户和数据明细见[交付与演示数据](docs/交付与演示数据.md)。

**修改源码后更新部署版**：`-PrepareImages`不会更新已存在的应用镜像，需要显式构建改动的服务，再执行日常启动命令：

```sh
# 同时修改前后端时；只修改其中一个时仅填写backend或frontend。
docker compose --env-file config/deployment.env -f config/compose.yaml -f config/compose.build.yaml build backend frontend
```

同一台电脑无需导出或导入镜像；只改运行配置无需构建。请勿使用 `down -v` 删除数据卷。

### 首次交付：导出 / 导入

制作者先完成镜像准备，再导出已有的四个运行镜像（导出脚本不会构建或拉取）：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Export-System.ps1
```

将生成的 `release/holiday-booking-版本-时间戳/` **整个目录**交给成员。接收方在该目录执行：

```powershell
docker load --input images.tar
if (-not (Test-Path config/deployment.env)) {
    Copy-Item config/deployment.env.example config/deployment.env
}
notepad config/deployment.env
# 填好自己的密码后，使用第2节的日常 Docker 启动命令。
```

接收方不需要源码、Java、Node。镜像含预设数据装载程序；首次启动生成相同业务场景，不复制制作者的密码或工作数据库。校验包、升级和账户读取详见交付文档。



## 3. 开发版：首次准备与日常启动

首次缺少配置、依赖或后端 JAR 时执行（已有配置不覆盖）：

```powershell
if (-not (Test-Path config/development.env)) {
    Copy-Item config/development.env.example config/development.env
}
notepad config/development.env
docker compose --env-file config/development.env -f config/compose.dev.yaml pull --policy missing mysql mailpit
npm --prefix frontend ci
.\backend\mvnw.cmd -f .\backend\pom.xml -DskipTests package
```

日常一条命令启动数据库、Mailpit、后端和前端：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-System.ps1 -Mode Development
```

打开 **http://localhost:5173**。直接运行已有 JAR 和 Vite 热更新服务，不执行 Maven、npm 安装或前端构建。日志在 `.local/run-时间戳/`；保持终端打开，Ctrl+C 停止本次前后端，数据库保留。新库先填写管理员配置，首次追加 `-InitializeAdmin`；已有管理员无需重复初始化。

需要分开运行时，在两个终端分别执行：

```powershell
# 终端一：数据库、Mailpit和后端
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Backend.ps1

# 终端二：Vue开发服务器
npm --prefix frontend run dev
```

修改 Vue 页面自动热更新。修改 Java 后，手动执行上面的 Maven 打包命令再重启后端；依赖锁文件变化时才手动执行 `npm ci`。开发排错、Swagger、管理员和测试说明见[运行指南](docs/运行指南.md)。旧参数 `-Mode Source` 仍兼容，含义同 Development。

## 4. 项目结构与文档

```text
config/     开发/部署环境配置、运行Compose、单独构建配置
backend/    Java业务、REST API、数据库迁移、Java测试、Dockerfile
frontend/   Vue角色门户、API客户端、前端测试、Dockerfile
scripts/    整体/后端启动、镜像导出、模块测试入口
docs/       运行、交付、渠道、测试说明及设计资料
results/    测试执行后生成的日志与结果
release/    生产环境发行版本，可以直接配置并运行
```

[后端功能与运行](backend/README.md) · [前端功能与运行](frontend/README.md) · [文档导航](docs/README.md)

部署版提供构建后的网页；浏览器可查看运行所需的 JS，密钥与权限校验仍由后端管理。默认服务绑定本机回环地址；跨机器提供访问时还需配置入口和 HTTPS。mock 不发生真实资金交易，Mailpit 不向互联网邮箱投递，但两者均在本项目部署栈中启用。
