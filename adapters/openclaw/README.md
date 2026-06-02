# OpenClaw MCP Integration

OpenClaw 侧的 MCP 配置方式取决于实际版本。可以参考 `adapters/openclaw/mcp-server.example.json`，将 Family Repurchase MCP Server 配置为 stdio MCP server。
OpenClaw 可以作为 MCP Host 连接 Family Repurchase Agent，并通过 MCP tools 使用本地复购品价格决策能力。

标准调用链路：

```text
OpenClaw
  -> Family Repurchase MCP Server
  -> Spring Boot REST Tool API
  -> Application Service / Domain / SQLite
```

## MCP Server

Family Repurchase Agent 通过 Java MCP stdio Server 暴露工具能力。

当前 MCP tools：

* `import_file`：导入本地 CSV / Excel 订单文件
* `compare_price`：将当前商品单位价格与本地历史购买记录进行比较
* `generate_report`：生成指定月份的复购品价格报告

OpenClaw 应通过 MCP Server 调用这些 tools。Agent Host 不应直接访问 SQLite、Repository、Domain Service，也不应绕过 Spring Boot 后端自行计算价格结论。

## 启动前提

先启动 Spring Boot 后端：

```bash
mvn package
java -jar target/family-repurchase-agent-0.4.0.jar
```

然后构建 MCP Server：

```bash
mvn -f adapters/mcp/family-repurchase-mcp-java-server/pom.xml package
```

MCP Server 启动命令：

```bash
java -jar adapters/mcp/family-repurchase-mcp-java-server/target/family-repurchase-mcp-java-server-0.4.0.jar
```

## 环境变量

```text
FAMILY_AGENT_API_BASE_URL=http://127.0.0.1:8080
FAMILY_AGENT_IMPORT_ALLOWED_DIRS=<允许导入的目录>
```

Windows 示例：

```powershell
$env:FAMILY_AGENT_API_BASE_URL = "http://127.0.0.1:8080"
$env:FAMILY_AGENT_IMPORT_ALLOWED_DIRS = "examples;data/imports;imports"
```

## 添加到 OpenClaw


推荐使用本目录下的 PowerShell 脚本注册 MCP Server：

```powershell
powershell -ExecutionPolicy Bypass -File .\adapters\openclaw\openclaw-set-mcp.ps1 -Show
```

脚本会生成当前项目可用的 MCP server definition，并调用：
```powershell
openclaw mcp set family-repurchase-agent <json>
```

注册完成后，可以查看配置：

```powershell
openclaw mcp list
openclaw mcp show family-repurchase-agent
```
如果需要手动注册，可以参考：

```powershell
$json = Get-Content -Raw .\adapters\openclaw\mcp-server.local.json |
ConvertFrom-Json |
ConvertTo-Json -Depth 20 -Compress

$jsonEscaped = $json -replace '"', '\"'

openclaw mcp set family-repurchase-agent $jsonEscaped
```
其中 mcp-server.local.json 可以从 mcp-server.example.json 复制后修改得到。注意：mcp-server.example.json 中的 <PROJECT_ROOT> 是占位符，不能直接用于真实注册。

## 调试说明

MCP Inspector 可用于本地调试 MCP Server，例如验证 tools、schema 和调用结果。

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\inspect-mcp.ps1
```

MCP Inspector 只是调试工具；OpenClaw 才是实际 Agent Host。
