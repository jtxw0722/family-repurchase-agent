# Family Repurchase Agent

[![CI](https://github.com/jtxw0722/family-repurchase-agent/actions/workflows/ci.yml/badge.svg)](https://github.com/jtxw0722/family-repurchase-agent/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-17%2B-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen)
![Release](https://img.shields.io/github/v/release/jtxw0722/family-repurchase-agent?display_name=tag)

> 本地优先的家庭复购品价格决策 Agent，基于 Java、Spring Boot、SQLite 和 MCP Server 构建，可供 Claude Code、Codex、OpenClaw 等 Agent Host 通过 MCP 调用。

**Family Repurchase Agent** 面向猫砂、纸巾、洗衣液等家庭高频复购品，核心问题不是“花了多少钱”，而是：

> 这个价格现在值不值得买？

系统会将不同规格、不同单位、不同包装形式的商品价格归一化为单位价格，并结合本地历史购买记录计算价格基准线，判断当前价格是否值得购买。价格结论基于后端工具返回的单位价格、历史基准线、样本数量、evidence 和 warnings，而不是让 LLM 凭常识猜测。

---

## 核心能力

- CSV / Excel 订单文件导入
- SQLite 本地存储
- 商品名称归一化
- 规格解析与单位价格计算
- 当前价格与本地历史价格对比
- 历史价格基准线查询
- 重复记录检测
- 异常样本人工复核
- Markdown 复购品价格报告
- REST Tool API
- CLI 辅助命令
- Java MCP stdio Server
- Claude Code / Codex / OpenClaw 的 MCP 接入说明

---

## 技术栈

| 类型 | 选型 |
|---|---|
| 语言 | Java 17+ |
| 框架 | Spring Boot 3.x |
| 数据库 | SQLite |
| 文件导入 | Apache Commons CSV / Apache POI |
| 测试 | JUnit 5 |
| 构建 | Maven |
| Agent 集成 | Java MCP stdio Server / Claude Code / Codex / OpenClaw MCP Host |

---

## 架构

```text
Claude Code / Codex / OpenClaw
        ↓
Family Repurchase MCP Server
        ↓
Spring Boot REST Tool API
        ↓
Application Service / Domain / SQLite
```

Spring Boot 后端负责业务规则和本地数据处理。Java MCP Server 是统一的 Agent Host 工具出口。Agent Host 不应直接访问 SQLite、Repository 或 Domain Service，也不应绕过后端业务规则自行计算价格结论。

REST Tool API 仍作为后端内部工具入口保留：

- `/api/tools/import-file`
- `/api/tools/compare-price`
- `/api/tools/get-price-baseline`
- `/api/tools/generate-report`

MCP tools：

- `import_file`
- `compare_price`
- `get_price_baseline`
- `generate_report`

---

## 快速开始

环境要求：

```bash
java -version
mvn -version
```

需要 JDK 17+ 和 Maven 3.8+。

运行测试：

```bash
mvn test
```

打包并启动后端：

```bash
mvn package
java -jar target/family-repurchase-agent-0.4.0.jar
```

默认本地目录：

```text
data/family-repurchase.sqlite
data/inbox/
reports/
```

OpenAPI：

```text
http://localhost:8080/swagger-ui.html
```

---

## REST 示例

### 导入文件

```powershell
curl -X POST "http://localhost:8080/api/tools/import-file" `
  -H "Content-Type: application/json" `
  -d "{\"filePath\":\"examples/sample_orders.csv\",\"owner\":\"jtxw\"}"
```

### 判断当前价格

```powershell
curl -X POST "http://localhost:8080/api/tools/compare-price" `
  -H "Content-Type: application/json" `
  -d "{\"productName\":\"猫砂\",\"price\":89,\"quantity\":12,\"unit\":\"kg\"}"
```

### 查询历史价格基准线

```powershell
curl -X POST "http://localhost:8080/api/tools/get-price-baseline" `
  -H "Content-Type: application/json" `
  -d "{\"productName\":\"纸巾\",\"unit\":\"抽\"}"
```

### 生成报告

```powershell
curl -X POST "http://localhost:8080/api/tools/generate-report" `
  -H "Content-Type: application/json" `
  -d "{\"month\":\"2026-05\"}"
```

---

## CLI 示例

```bash
java -jar target/family-repurchase-agent-0.4.0.jar import examples/sample_orders.csv --owner=jtxw

java -jar target/family-repurchase-agent-0.4.0.jar price "猫砂" --price=89 --quantity=12 --unit=kg

java -jar target/family-repurchase-agent-0.4.0.jar report --month=2026-05
```

---

## MCP / Agent 集成

Family Repurchase Agent 通过 Java MCP stdio Server 暴露工具能力。Claude Code、Codex、OpenClaw 等 Agent Host 应通过 MCP 连接本项目，而不是直接访问数据库或绕过后端业务规则。

当前 MCP tools：

| Tool | 作用 | 典型问题 |
|---|---|---|
| `import_file` | 导入本地 CSV / Excel 订单文件 | “导入 examples/sample_orders.csv” |
| `compare_price` | 基于当前价格和历史样本判断是否值得买 | “猫砂 10.3 元 5kg 值得买吗？” |
| `get_price_baseline` | 查询某个复购品的历史价格基准线 | “查一下猫砂历史最低价 / 平均价” |
| `generate_report` | 生成指定月份的本地 Markdown 价格报告 | “生成 2026-05 的复购品报告” |

工具选择建议：

| 用户意图 | 推荐 MCP tool |
|---|---|
| 导入订单文件 | `import_file` |
| 判断当前价格是否值得买 | `compare_price` |
| 查询历史最低价 / 中位价 / 平均价 / 价格基准线 | `get_price_baseline` |
| 生成月度报告 | `generate_report` |

`compare_price` 和 `get_price_baseline` 的区别：

- `compare_price` 用于“我现在看到一个价格，是否值得买”，需要提供当前价格、数量和单位。
- `get_price_baseline` 用于“我想查这个商品历史最低价 / 中位价 / 平均价”，不需要提供当前价格。

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

MCP Inspector 仅用于本地调试 MCP Server，不是最终使用入口。

---

## 项目结构

```text
src/main/java/com/jtxw/familyagent/
├── application/        # 应用服务
├── domain/             # 领域模型与规则
├── infrastructure/     # SQLite、导入器、报告输出
└── interfaces/         # REST API 和 CLI

adapters/
├── mcp/                # Java MCP stdio Server
├── openclaw/           # OpenClaw MCP 接入说明、示例配置和注册脚本
├── claude-code/        # Claude Code MCP 接入说明
└── codex/              # Codex MCP 接入说明和 Skill

docs/
└── tool_contract.md    # MCP tools 契约说明

scripts/
└── inspect-mcp.ps1     # MCP Inspector 本地调试脚本

assets/screenshots/     # Agent Host 调用 MCP tools 的示例截图
examples/               # 合成示例数据
```

适配器文档：

- [Agent Host Integration](adapters/README.md)
- [MCP Java Server](adapters/mcp/family-repurchase-mcp-java-server/README.md)
- [OpenClaw MCP Integration](adapters/openclaw/README.md)
- [Tool Contract](docs/tool_contract.md)

---

### Agent Host 示例

#### Claude Code
![Claude Code MCP Demo](/assets/screenshots/claude-code-mcp-compare-price.png)

#### CodeX
![CodeX MCP Demo](/assets/screenshots/codex-mcp-compare-price.png)

#### OpenClaw
![OpenClaw MCP Demo](/assets/screenshots/openclaw-mcp-compare-price.png)

---

## 隐私与安全

Family Repurchase Agent 默认本地运行。

- 数据默认保存在本机 SQLite
- 不登录电商平台
- 不读取 Cookie 或浏览器会话
- 不爬取购物网站
- 不自动下单
- 不上传真实订单数据到云端
- `examples/` 只使用合成示例数据

---

## 设计取舍

- Local-first：购买记录默认保存在本机，避免上传家庭消费数据。
- SQLite：项目面向单人 / 单家庭使用，SQLite 足以支撑本地历史样本查询。
- 独立 MCP Server：MCP 只做协议适配和转发，业务规则保留在 Spring Boot 后端。
- 工具计算优先：LLM 负责理解意图和解释结果，价格判断由后端基于历史样本计算。
- 价格判断阈值当前采用可配置的启发式 MVP 规则：当前单价低于历史最低价，或低于历史中位价一定比例时判断为好价；明显高于历史中位价时判断为偏贵。具体阈值和工具返回契约见 [Tool Contract](docs/tool_contract.md)。

---

## Roadmap

- `v0.5`: `record_price_sample`
  - 自然语言输入
  - 槽位提取
  - dry-run `PurchaseCandidate`
  - 用户确认后写入正式样本库
- `v0.6`: `compare_current_price`
    - 更强的单位换算模型
    - 更细粒度的历史价格趋势分析
    - warning 模型
    - 可解释购买建议
- `v0.7`: `import_price_samples` / `generate_price_report`
  - 批量价格样本导入
  - 样本质量摘要
  - 面向价格决策的报告模板

---

## License

MIT
