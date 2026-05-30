# Family Repurchase Agent Claude Code Rules

本项目通过 Java MCP Server 供 Claude Code 以 MCP Host 身份调用家庭复购品价格决策工具。

Claude Code 用户可以将 `adapters/claude-code/.mcp.example.json` 复制到项目根目录并重命名为 `.mcp.json`，再按本机路径调整。

Claude Code 应通过已配置的 MCP Server 使用本项目能力，不应绕过 MCP Server 或 Spring Boot 后端直接访问 SQLite、Repository 或 Domain Service。

规则：

- 默认中文输出；
- 复购品价格判断优先使用已配置的 MCP tools；
- 不直接凭模型常识判断价格是否划算；
- 不提交真实订单数据、SQLite 数据库、`.env` 或生成报告；
- 如需修改项目代码，修改后运行相关测试；
- 不修改 REST endpoint、MCP tool name、数据库 schema、Java package，除非用户明确要求。
