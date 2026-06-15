# Family Repurchase MCP Java Server

这是 Family Repurchase Agent 的统一 MCP Tool Server。

Claude Code、Codex、OpenClaw 等 Agent Host 都应通过它调用项目能力。

调用链路：

```text
Claude Code / Codex / OpenClaw
        ↓
Family Repurchase MCP Server
        ↓
Spring Boot REST Tool API
        ↓
Application Service / Domain / SQLite
```

MCP Server 只做协议适配、参数校验、文件路径防护和 HTTP 转发，不直接访问 SQLite、Repository 或 Domain Service。

## MCP Tools

| Tool                 | 说明                                  | 典型场景                            |
| -------------------- | ----------------------------------- | ------------------------------- |
| `import_file`        | 导入本地 CSV 或 Excel 订单文件，并生成购买记录和待复核记录 | “导入 examples/sample_orders.csv” |
| `record_purchase`    | 录入手动购买记录或自然语言抽取后的结构化购买记录 | “记录一下，我在京东买了猫砂2.5kg*8，花了178.65元” |
| `compare_price`      | 不传 price / quantity / unit 时查询全家庭历史价格基准线；同时传入三者时返回与全家庭基准线的价格比较结果 | “猫砂历史最低价是多少？” / “猫砂 10.3 元 5kg 值得买吗？” |
| `search_purchase_records` | 根据关键词检索原始购买记录，查看历史订单明细和样本来源；owner 仅是订单归属过滤条件 | “查一下我之前买过哪些猫砂” |
| `generate_report`    | 根据指定月份生成 Markdown 价格报告              | “生成 2026-05 的复购品报告”             |

工具选择建议：

| 用户意图                        | 推荐 Tool              |
| --------------------------- | -------------------- |
| 导入订单文件                      | `import_file`        |
| 记录一笔购买记录 / 从自然语言补充历史样本 | `record_purchase`    |
| 判断当前价格是否值得买                 | `compare_price`      |
| 查询历史最低价 / 中位价 / 平均价 / 价格基准线 | `compare_price`（只传 productName） |
| 查询具体购买记录 / 排查历史样本来源 | `search_purchase_records` |
| 生成月度报告                      | `generate_report`    |

`owner` 表示订单归属人，用于溯源、检索过滤和重复检测辅助。默认价格基准、比价分析和规则维护使用全家庭样本；`search_purchase_records` 返回原始记录，不生成可靠价格基线。

## 构建

在项目根目录执行：

```bash
mvn -f adapters/mcp/family-repurchase-mcp-java-server/pom.xml package
```

生成产物：

```text
adapters/mcp/family-repurchase-mcp-java-server/target/family-repurchase-mcp-java-server.jar
```

## 运行

先启动主项目 Spring Boot 服务：

```bash
mvn package
java -jar target/family-repurchase-agent.jar
```


再启动 MCP Server：

```bash
java -jar adapters/mcp/family-repurchase-mcp-java-server/target/family-repurchase-mcp-java-server.jar
```

默认后端地址：

```text
http://127.0.0.1:8080
```

可以通过环境变量覆盖：

```bash
FAMILY_AGENT_API_BASE_URL=http://127.0.0.1:8080
```

Windows PowerShell：

```powershell
$env:FAMILY_AGENT_API_BASE_URL = "http://127.0.0.1:8080"
```

## 文件导入安全目录

默认情况下，`import_file` 只允许导入以下目录下的 CSV / Excel 文件：

- `examples/`
- `data/imports/`
- `imports/`

可以通过 `FAMILY_AGENT_IMPORT_ALLOWED_DIRS` 覆盖。多个目录使用系统路径分隔符：

Linux / macOS：

```bash
FAMILY_AGENT_IMPORT_ALLOWED_DIRS=examples:data/imports:imports
```

Windows：

```powershell
$env:FAMILY_AGENT_IMPORT_ALLOWED_DIRS = "examples;data/imports;imports"
```

在 Agent Host 中配置时建议使用绝对路径，避免不同启动目录导致相对路径解析不一致。

## Agent Host 配置

Claude Code、Codex、OpenClaw 等 Host 的 MCP 配置方式取决于各自版本。项目侧提供 stdio MCP Server 命令：

```bash
java -jar adapters/mcp/family-repurchase-mcp-java-server/target/family-repurchase-mcp-java-server.jar
```

请按对应 Host 的 MCP 配置方式，将该命令配置为 stdio MCP server。

常见测试提示词：

记录购买记录时，可以使用：

```text
调用 Family Repurchase Agent MCP tools，记录一下：我在京东买了猫砂2.5kg*8，花了178.65元。
```

预期调用：
```text
record_purchase
```

查询历史均价时，可以使用：

```text
调用 Family Repurchase Agent MCP tools，查询猫砂历史最低价和历史平均价。
```

预期调用：
```text
compare_price
```

这段能减少 Agent Host 把“查历史最低价”误路由到 `generate_report` 的概率。

---

## 本地 smoke test

smoke test 不依赖 Spring Boot 后端，只验证 MCP stdio 协议层和工具列表。

PowerShell：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test-mcp.ps1
```

期望结果：

- `initialize` 返回成功
- `tools/list` 返回 `import_file`、`record_purchase`、`compare_price`、`search_purchase_records`、`generate_report`
- stdout 只输出 JSON-RPC 消息
- `compare_price` 只传 productName 时返回 baseline_only；price、quantity、unit 半完整时返回 MCP tool error，不会让 server 崩溃

## MCP Inspector

MCP Inspector 仅用于本地调试 MCP Server，不是最终 Agent Host。

从项目根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\inspect-mcp.ps1
```

如果当前位于 MCP 子模块目录，也可以执行：

```powershell
powershell -ExecutionPolicy Bypass -File ..\..\..\scripts\inspect-mcp.ps1
```

脚本默认会完成以下配置：

- 将 `FAMILY_AGENT_API_BASE_URL` 设置为 `http://127.0.0.1:8080`
- 将 `FAMILY_AGENT_IMPORT_ALLOWED_DIRS` 设置为 `examples`、`data/imports` 和 `imports`
- 自动在 `target/` 目录下定位 MCP Server jar
- 启动 MCP Inspector：

```powershell
npx -y "@modelcontextprotocol/inspector" java -jar <mcp-jar>
```

该脚本不会自动启动 Spring Boot 后端。使用前请先确保后端服务已经运行。

如果只验证 `tools/list`，可以使用 smoke test；如果需要实际调用 `compare_price`、`generate_report`，则必须先启动 Spring Boot 后端。

## 验证命令

从项目根目录执行：

```bash
mvn test
mvn -f adapters/mcp/family-repurchase-mcp-java-server/pom.xml test
mvn -f adapters/mcp/family-repurchase-mcp-java-server/pom.xml package
```

PowerShell smoke test：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test-mcp.ps1
```

如果需要使用 MCP Inspector 或 Agent Host 实际调用业务工具，请先启动 Spring Boot 后端：

```bash
mvn package
java -jar target/family-repurchase-agent.jar
```
