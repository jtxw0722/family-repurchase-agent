# Codex MCP Integration

本目录用于说明 Codex 如何通过 MCP 使用 Family Repurchase Agent。

Family Repurchase Agent 的标准工具出口是 Java MCP Server。Codex 应作为 MCP Host 连接该 MCP Server，然后调用以下 tools：

- `import_file`
- `record_purchase`
- `compare_price`
- `search_purchase_records`
- `generate_report`

## 文件说明

| 文件 | 作用 |
|---|---|
| `config.example.toml` | Codex MCP 配置示例，用于说明如何连接 Family Repurchase MCP Server |
| `skills/family-repurchase/SKILL.md` | Codex Skill，用于指导 Codex 在复购品价格判断、自然语言购买记录录入、订单导入、报告生成等场景下优先使用 MCP tools |
## MCP 配置

Codex 的 MCP Server 配置应写入 `config.toml`。

可以选择：

- 用户级配置：`~/.codex/config.toml`
- 项目级配置：`.codex/config.toml`

项目级配置只会在 Codex 信任该项目时加载。

使用方式：

1. 复制示例配置：
```powershell
mkdir .codex
copy adapters\codex\config.example.toml .codex\config.toml
```

2. 根据本机环境调整：
- MCP Server jar 路径
- FAMILY_AGENT_API_BASE_URL
- FAMILY_AGENT_PROJECT_ROOT
- FAMILY_AGENT_IMPORT_ALLOWED_DIRS

3. 启动 Spring Boot 后端：
```powershell
mvn package
java -jar target\family-repurchase-agent.jar
```

4. 在 Codex 中检查 MCP Server 是否可用。 
如果 Codex CLI 支持 /mcp，可以在 TUI 中查看已连接的 MCP servers。

## Codex Desktop 配置

如果使用 Codex Desktop，可以在 Codex Desktop 的 MCP 配置界面中手动添加 Family Repurchase MCP Server。

推荐配置如下：

| 配置项  | 值                         |
| ---- | ------------------------- |
| 名称   | `family-repurchase-agent` |
| 类型   | `STDIO`                   |
| 启动命令 | `java`                    |
| 工作目录 | 项目根目录，例如 `<PROJECT_ROOT>` |

参数需要分成两项填写：

```text
-jar
```

```text
<PROJECT_ROOT>\adapters\mcp\family-repurchase-mcp-java-server\target\family-repurchase-mcp-java-server.jar
```

环境变量：

| 变量名                                | 示例值                                                                          |
| ---------------------------------- | ---------------------------------------------------------------------------- |
| `FAMILY_AGENT_API_BASE_URL`        | `http://127.0.0.1:8080`                                                      |
| `FAMILY_AGENT_PROJECT_ROOT`        | `<PROJECT_ROOT>`                                                             |
| `FAMILY_AGENT_IMPORT_ALLOWED_DIRS` | `<PROJECT_ROOT>\examples;<PROJECT_ROOT>\data\imports;<PROJECT_ROOT>\imports` |

注意：

* `<PROJECT_ROOT>` 需要替换为本机项目根目录。
* Windows 下 `FAMILY_AGENT_IMPORT_ALLOWED_DIRS` 使用 `;` 分隔多个目录。
* 如果 Codex Desktop 找不到 `java`，可以将启动命令改成本机 Java 绝对路径，例如 `<JAVA_HOME>\bin\java.exe`。
* Codex Desktop 的具体界面字段可能随版本变化，以上配置的核心是：使用 `STDIO` 类型，以 `java -jar <MCP Server jar>` 启动 Family Repurchase MCP Server。

配置完成后，先启动 Spring Boot 后端：

```powershell
cd <PROJECT_ROOT>
java -jar target\family-repurchase-agent.jar
```

然后在 Codex Desktop 中测试：

```text
膨润土猫砂 10.3 元 5kg，结合本地历史购买记录判断是否值得买。请使用 Family Repurchase Agent MCP tools。
```

预期行为：

* Codex Desktop 调用 `compare_price`
* 返回当前单位价格、历史最低价、历史中位价、样本数量、evidence 和 warnings
* 最终回答基于 MCP tool 返回结果，而不是直接凭模型常识判断

`compare_price` 默认使用全家庭历史样本。`owner` 只表示订单归属人，用于导入、录入、检索过滤和重复检测辅助；在 `search_purchase_records` 中传入 owner 只会缩小原始记录检索范围，不生成价格基线。

