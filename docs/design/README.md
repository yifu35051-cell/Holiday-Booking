# 当前设计与历史资料

[返回文档导航](../README.md) · [当前实现状态](../../README.md)

本目录同时保存当前基线与历史规划。先看下面两份当前材料；旧 CSV 是设计计划，不是当前 Java 用例清单或执行结果。

| 资料 | 用途 |
| --- | --- |
| [整改实施审查](Implementation-Review.md) | G01–G13、S01–S10及Appendix 2逐节状态、实际证据与未关闭门槛 |
| [当前设计基线](Current-Design-Baseline.md) | WireMock/DirectMail架构、契约、图、CRC、数据及SRS追踪，供正式设计报告收敛 |
| [Design Report 逐节审查与改进清单](Design-Report-逐节审查与改进清单.md) | 对照 Appendix 2 核查当前系统；按模板列出实现、设计、文档与验证缺口及整改标准（2026-11-07） |
| [技术文档](Holiday-Booking-开发技术文档.md) / [离线阅读版](Holiday-Booking-开发技术文档.html) | 需求、技术栈、架构分层、业务规则及后续设计 |
| [需求追踪表](requirements-traceability.csv) | 原始需求与设计映射 |
| [测试场景规划](test-cases.csv) | 设计阶段场景；当前可执行用例见[测试入口](../testing/README.md) |
| [变更设计检查](change-design-checks.csv) | 取消、改订、提醒等变更的设计检查 |
| [来源清单](source-manifest.json) | 原始 PDF 与主稿校验值 |

`source-extracts/` 保存 PDF 提取文本，`assets/` 保存图，`tools/` 保存文档生成工具。这些支撑资料供追溯和维护，不必在日常测试时阅读。

需要重新生成离线版时，在项目根目录运行 `python docs/design/tools/build_docs.py`（需要 Python 的 `markdown` 包）。该工具还会重建三个计划 CSV，运行前应保留已填写内容；测试实际结果始终保存在项目 `results/` 下。
