# Agent Host Integration

本目录用于存放 Family Repurchase Agent 面向不同 Agent Host 的 MCP 接入说明。

Family Repurchase Agent 的统一工具出口是 Java MCP Server。Claude Code、Codex、OpenClaw 等 Agent Host 都应通过 MCP Server 调用项目能力。

## MCP Tool Server

| 路径 | 说明                                                                                            |
|---|-----------------------------------------------------------------------------------------------|
| `mcp/family-repurchase-mcp-java-server` | Java MCP stdio Server，暴露 `import_file`、`record_purchase`、`compare_price`、`search_purchase_records`、`generate_report` |

## MCP Server 运行模式

### 模式一：源码构建模式

适合本地开发者、未使用 Jenkins 的用户。

MCP jar 路径：

`<PROJECT_ROOT>/adapters/mcp/family-repurchase-mcp-java-server/target/family-repurchase-mcp-java-server.jar`

构建命令：

```powershell
mvn -f adapters\mcp\family-repurchase-mcp-java-server\pom.xml clean package -DskipTests
```

### 模式二：Jenkins 发布模式

适合本机进行了 Jenkins 配置。

Jenkins Pipeline 会在 `Build MCP Server` 和 `Smoke Test MCP` 通过后，将 MCP Server jar 发布到本地运行目录。

发布目录由 Jenkins 参数 `MCP_RUNTIME_DIR` 控制 ,默认发布后的 MCP jar 路径为：

```text
D:\mcp-runtime\family-repurchase-agent\family-repurchase-mcp-java-server.jar
```

---

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

`owner` 表示订单归属人，用于溯源、检索过滤和重复检测辅助。默认价格基准、比价分析和规则维护使用全家庭样本；`search_purchase_records` 中的 owner 仅是原始记录过滤条件。

