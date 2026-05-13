# 架构说明

本项目采用 Java / Spring Boot 分层架构：

```text
interfaces     对外入口：CLI、REST Tool API
application    应用服务：编排导入、比价、报告、复核流程
domain         领域模型与规则：商品标准化、单价计算、价格判断
infrastructure 数据库、文件导入、报告输出等技术实现
adapters       OpenClaw / Codex / Claude Code 适配文件
```

Agent Runtime 不直接访问数据库，而是通过 Tool API 调用受控能力。

## 技术栈选型理由

| 类型 | 选型 | 选择理由 |
|---|---|---|
| 开发语言 | Java 17+ | Java 生态成熟，适合构建稳定的后端服务、分层架构、数据库访问、测试体系和长期可维护的业务逻辑。Spring Boot 3.x 要求 Java 17 及以上版本，因此选择 Java 17+ 作为基础版本。 |
| 框架 | Spring Boot 3.x | Spring Boot 适合作为后端应用底座，便于实现 REST Tool API、Service 分层、配置管理、测试和后续 Web / MCP / Plugin 扩展。当前阶段项目核心是结构化数据处理和确定性计算，而不是直接调用大模型，所以先使用 Spring Boot 构建稳定业务能力，Spring AI 后续作为增强层引入。 |
| 数据库 | SQLite | 项目当前定位是本地优先的个人 Agent，订单数据属于隐私数据。SQLite 不需要独立数据库服务，适合本地运行、低成本部署和单用户数据持久化。后续如果演进为多人 Web 服务，可以通过 Repository 层切换到 MySQL、PostgreSQL 或 Oracle。 |
| 文件导入 | Apache Commons CSV | v0.1 阶段优先支持 CSV 导入，Apache Commons CSV 是成熟的 Java CSV 读写库，适合处理本地订单导出文件。后续会补充 Apache POI 支持 Excel 导入。 |
| 测试 | JUnit 5 | 项目涉及金额、数量、单位价格、统计口径等确定性逻辑，需要通过单元测试保证结果稳定。JUnit 5 是 Java 生态中常用的测试框架，适合覆盖导入、单位换算、价格判断、复核流程等核心逻辑。 |
| 构建工具 | Maven | Maven 是 Java 后端项目中常用的依赖管理和构建工具，适合管理 Spring Boot、SQLite JDBC、Commons CSV、JUnit 等依赖，也便于接入 GitHub Actions 做 CI。 |