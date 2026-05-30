# Agent Host Integration

本目录用于存放 Family Repurchase Agent 面向不同 Agent Host 的 MCP 接入说明。

Family Repurchase Agent 的统一工具出口是 Java MCP Server。Claude Code、Codex、OpenClaw 等 Agent Host 都应通过 MCP Server 调用项目能力。

## MCP Tool Server

| 路径 | 说明 |
|---|---|
| `mcp/family-repurchase-mcp-java-server` | Java MCP stdio Server，暴露 `import_file`、`compare_price`、`generate_report` |

## Agent Host 接入

| 路径 | 说明 |
|---|---|
| `claude-code` | Claude Code 通过 MCP 使用 Family Repurchase Agent 的说明和约束 |
| `codex` | Codex 通过 MCP 使用 Family Repurchase Agent 的说明和约束 |
| `openclaw` | OpenClaw 通过 MCP 使用 Family Repurchase Agent 的说明 |

## Debugging

| 工具 | 说明 |
|---|---|
| MCP Inspector | 本地调试 MCP Server，验证 tools、schema 和调用结果 |

## Boundary / 边界

Spring Boot 后端负责业务规则和本地数据处理。

Java MCP Server 是统一的 Agent Host 工具出口。

Claude Code、Codex、OpenClaw 作为 MCP Host / MCP Client 调用 MCP tools。

Agent Host 不应直接访问 SQLite、Repository 或 Domain Service，也不应绕过后端业务规则自行计算价格结论。
