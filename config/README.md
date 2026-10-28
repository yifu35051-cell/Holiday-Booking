# 运行配置

日常运行命令统一看[项目README](../README.md)。本目录只集中环境配置和Docker编排；Java、Vue各自的构建工具配置仍保留在模块目录。

| 文件 | 用途 | 是否提交/交付 |
| --- | --- | --- |
| `deployment.env` | 生产/部署版：端口、数据库密码、首次管理员、镜像版本 | 否，每个成员自行配置 |
| `deployment.env.example` | 生产/部署配置模板 | 是 |
| `compose.yaml` | 生产/部署运行：前端、后端、MySQL、Mailpit，只用本地镜像 | 是，镜像接收方使用 |
| `compose.build.yaml` | 显式构建两个应用镜像；日常启动不加载此文件 | 是，仅源码构建时需要 |
| `development.env` | 开发版：原开发库、后端、holiday-mailpit配置 | 否，保留已有密码 |
| `development.env.example` | 开发配置模板 | 是 |
| `compose.dev.yaml` | 开发版只容器化MySQL和Mailpit；Java/Vue在本机运行 | 是 |

迁移对应：原根目录`.env.mysql` → `development.env`，`.env.docker` → `deployment.env`；`compose.mysql.yaml` → `compose.dev.yaml`，`compose.app.yaml` → `compose.yaml`。本次迁移保留配置内容和Compose项目名称：开发版仍是`holiday-booking`，部署版仍是`holiday-booking-demo`。两套数据库保持各自原有的数据卷。

生产/部署版继续启用mock支付与Mailpit邮箱。开发版可以在自己的配置里选择渠道。镜像启动与渠道开关是两件独立的事；无需为了运行这两个功能去使用开发服务器。

两份Compose引用完全相同的 `mysql:8.4`、`axllent/mailpit:latest`，同一Docker引擎只需保留一份对应镜像。部署容器和数据卷独立，并不复制这些镜像。`APP_VERSION`仅决定两个项目应用镜像的标签，与MySQL/Mailpit无关；开发版本机Java/Vue不使用应用镜像。`Start-System.ps1 -Mode Docker -PrepareImages`显式准备缺少的镜像，已有项全部跳过；不加该参数的启动不会拉取或构建。

真实env文件已被Git忽略，也不会进入Docker构建上下文。模板只在首次缺文件时复制，不覆盖已有配置。不要修改已使用数据卷的数据库密码后期望MySQL自动同步；密码更改需单独处理。
