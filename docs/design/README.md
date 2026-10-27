# 原始需求与架构设计

[返回文档导航](../README.md) · [当前实现状态](../../README.md)

本目录保存设计阶段资料，包含尚未实现的规划。日常运行与测试请使用上面的导航；这里的 CSV 是设计计划，不是当前 Java 用例清单或执行结果。

| 资料 | 用途 |
| --- | --- |
| [技术文档](Holiday-Booking-开发技术文档.md) / [离线阅读版](Holiday-Booking-开发技术文档.html) | 需求、技术栈、架构分层、业务规则及后续设计 |
| [需求追踪表](requirements-traceability.csv) | 原始需求与设计映射 |
| [测试场景规划](test-cases.csv) | 设计阶段场景；当前可执行用例见[测试入口](../testing/README.md) |
| [变更设计检查](change-design-checks.csv) | 取消、改订、提醒等变更的设计检查 |
| [来源清单](source-manifest.json) | 原始 PDF 与主稿校验值 |

`source-extracts/` 保存 PDF 提取文本，`assets/` 保存图，`tools/` 保存文档生成工具。这些支撑资料供追溯和维护，不必在日常测试时阅读。

需要重新生成离线版时，在项目根目录运行 `python docs/design/tools/build_docs.py`（需要 Python 的 `markdown` 包）。该工具还会重建三个计划 CSV，运行前应保留已填写内容；测试实际结果始终保存在项目 `results/` 下。
