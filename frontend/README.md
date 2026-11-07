# Holiday Booking Frontend

Vue 3、TypeScript、Pinia、Vue Router 和原生 fetch。登录前选择顾客/员工/管理员入口；后端核验登录角色，登录后只能进入自己的工作区。页面守卫与后端权限共同生效。

## 功能

| 入口 | 界面能力 |
| --- | --- |
| 游客 `/explore` | 房型、详情和日期可用性；报价/预订前转顾客登录 |
| CUSTOMER | 客房与报价、个人预订详情、取消改订、会员方案/购买/续期、钱包与付款、退款状态、个人邮箱 |
| STAFF | 实体房/封房、所有预订筛选与详情、通知状态/人工重试、个人邮箱 |
| ADMIN | 员工页面、房型和价格、方案、用户角色、全邮件审计、异常复核/恢复预算释放、审计日志、个人邮箱 |

付款页面显示本地模拟方式、金额与到期时间。首次设置独立六位 PIN，模拟充值后才能付款；付款授权后等待渠道确认并自动查询本地业务状态。密码输入不会进入日志/浏览器持久存储。WireMock 不提供真实支付宝或微信收银台，不应输入真实支付密码。

各角色使用自己的站内邮箱，SMTP From 统一代表酒店，Reply-To 为真实业务发件人；发件失败和待核实状态可查看。SMTP 仅提供出站传输，QQ 等外部邮箱的回复不会自动成为站内来信。所有日期时间按酒店 `Asia/Shanghai` 展示。

## 开发运行

Node 版本遵循 `package.json` 的 `^22.18.0 || >=24.12.0`。首次在 `frontend` 目录执行 `npm ci`。后端启动方式见[后端 README](../backend/README.md)。

```powershell
# 项目根目录：以后日常一次启动前后端，不自动安装或构建
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-System.ps1 -Mode Development

# 或在 frontend 目录单独启动
npm run dev
```

开发访问 `http://localhost:5173`；API 通过 Vite 代理到后端。后端使用非默认端口时，启动前设置 `BACKEND_URL` 为对应地址。Vite 源码/调试工具只用于开发；可发布部署使用构建后的静态文件。

```powershell
# 主动验证/打包，不属于每次启动步骤
npm run type-check
npm run build
```

Docker 构建与启动见[根 README](../README.md)。镜像由 Nginx 提供静态页面，不使用 Vite；不交付 source map。运行时 Nginx 私有配置负责边缘鉴权标记，密钥不进入 Vue 编译变量或网页资源。浏览器能查看 HTML/JS 是 Web 的正常机制，安全依靠后端鉴权和不向浏览器发送秘密。

## 验证边界

当前 TypeScript 和格式检查通过，既有 Playwright 接口替身测试不能代表 WireMock + MySQL + DirectMail 的真实 E2E。完整角色流程、支付故障、键盘和 200% 缩放仍须实机验收。后端新增恢复接口已对应到管理员异常页面；不会把人工复核或“重新开放核验”显示成退款完成。

当前未提供入住登记/退房、密码找回和房型图片上传接口，前端也没有伪造这些功能。完整整改状态见[审查文档](../docs/design/Implementation-Review.md)。
