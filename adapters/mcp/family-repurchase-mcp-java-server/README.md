# Family Repurchase MCP Java Server

This module is the Java MCP stdio adapter for Family Repurchase Agent.

它只做协议适配、参数校验、文件路径防护和 HTTP 转发，不直接访问 SQLite，也不直接调用主项目的 Repository 或 Domain Service。

```text
Claude Desktop / Cursor
  -> MCP stdio
  -> Java MCP Server
  -> Spring Boot REST Tool API
  -> Application Service / Domain / SQLite
```

## Tools

当前暴露 3 个工具：

| Tool | 说明 |
|---|---|
| `import_file` | 导入本地 CSV 或 Excel 订单文件，并生成购买记录和待复核记录 |
| `compare_price` | 比较当前商品单位价格与本地历史价格，返回价格判断结果 |
| `generate_report` | 根据指定月份生成 Markdown 价格报告 |

`generate_report` 会通过后端生成本地 Markdown 报告文件，因此不是 read-only tool。

## 构建

在项目根目录执行：

```bash
mvn -f adapters/mcp/family-repurchase-mcp-java-server/pom.xml package
```

生成产物：

```text
adapters/mcp/family-repurchase-mcp-java-server/target/family-repurchase-mcp-java-server-0.4.0.jar
```

## 运行

先启动主项目 Spring Boot 服务：

```bash
mvn package
java -jar target/family-repurchase-agent-0.4.0.jar
```

再启动 MCP Server：

```bash
java -jar adapters/mcp/family-repurchase-mcp-java-server/target/family-repurchase-mcp-java-server-0.4.0.jar
```

默认后端地址：

```text
http://localhost:8080
```

可以通过环境变量覆盖：

```bash
FAMILY_AGENT_API_BASE_URL=http://localhost:8080
```

Windows PowerShell：

```powershell
$env:FAMILY_AGENT_API_BASE_URL = "http://localhost:8080"
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

如果在 Claude Desktop 中配置，建议使用绝对路径，避免不同启动目录导致相对路径解析不一致。

## Claude Desktop 配置示例

Windows 示例：

```json
{
  "mcpServers": {
    "family-repurchase-agent": {
      "command": "java",
      "args": [
        "-jar",
        "D:\\project\\family-repurchase-agent\\adapters\\mcp\\family-repurchase-mcp-java-server\\target\\family-repurchase-mcp-java-server-0.4.0.jar"
      ],
      "env": {
        "FAMILY_AGENT_API_BASE_URL": "http://localhost:8080",
        "FAMILY_AGENT_IMPORT_ALLOWED_DIRS": "D:\\project\\family-repurchase-agent\\examples;D:\\project\\family-repurchase-agent\\data\\imports;D:\\project\\family-repurchase-agent\\imports"
      }
    }
  }
}
```

macOS / Linux 示例：

```json
{
  "mcpServers": {
    "family-repurchase-agent": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/family-repurchase-agent/adapters/mcp/family-repurchase-mcp-java-server/target/family-repurchase-mcp-java-server-0.4.0.jar"
      ],
      "env": {
        "FAMILY_AGENT_API_BASE_URL": "http://localhost:8080",
        "FAMILY_AGENT_IMPORT_ALLOWED_DIRS": "/path/to/family-repurchase-agent/examples:/path/to/family-repurchase-agent/data/imports:/path/to/family-repurchase-agent/imports"
      }
    }
  }
}
```

## 本地 smoke test

smoke test 不依赖 Spring Boot 后端，只验证 MCP stdio 协议层和工具列表。

PowerShell：

```powershell
cd adapters/mcp/family-repurchase-mcp-java-server
powershell -ExecutionPolicy Bypass -File .\smoke-test.ps1
```

期望结果：

- `initialize` 返回成功
- `tools/list` 返回 `import_file`、`compare_price`、`generate_report`
- stdout 只输出 JSON-RPC 消息
- `compare_price` 参数缺失时返回 MCP tool error，不会让 server 崩溃

## 验证命令

```bash
mvn -f adapters/mcp/family-repurchase-mcp-java-server/pom.xml test
mvn -f adapters/mcp/family-repurchase-mcp-java-server/pom.xml package
```

