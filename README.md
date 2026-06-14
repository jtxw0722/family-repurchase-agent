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

* CSV / Excel 订单文件导入
* 自然语言 / 手动购买记录录入
* SQLite 本地存储
* 商品名称归一化规则库维护
* LLM 辅助规则维护建议：从历史购买记录筛选候选商品，建议新增规则或关键词
* 人工复核与规则库维护闭环，避免低置信结果直接污染价格基准
* 异常样本人工复核
* 规格解析与单位价格计算
* 当前价格与本地历史价格对比
* 历史价格基准线查询
* 重复记录检测与手动录入防御
* 购买记录溯源：`shopName`、`note`、`sourceText`
* Markdown 复购品价格报告
* REST Tool API / CLI / Java MCP stdio Server
* Claude Code / Codex / OpenClaw 的 MCP 接入说明

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
## 工程化与部署

项目支持本地 Jenkins CI/CD 流水线，用于自动化构建、验证和部署。

当前 Pipeline 覆盖：

* 构建 Spring Boot 后端 jar
* 构建 Java MCP stdio Server
* 执行 MCP smoke test，验证 MCP Server 可启动、`initialize` 正常、`tools/list` 能返回核心工具
* 将 Spring Boot jar 上传到轻量云服务器
* 通过 systemd 重启后端服务
* 可选将 MCP Server jar 发布到本机 runtime 目录，供 Claude Code / Codex / OpenClaw 等 Agent Host 使用

部署方案采用本地 Jenkins + 远程轻量服务器的方式：Jenkins 运行在 Windows 本地 PC，负责构建和发布；云服务器只运行 Spring Boot 后端，服务监听 `127.0.0.1:8080`，通过 SSH Tunnel 提供本地安全访问。

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
- `/api/tools/record-purchase`
- `/api/tools/compare-price`
- `/api/tools/get-price-baseline`
- `/api/tools/generate-report`
- `/api/tools/review-items`
- `/api/tools/review-items/{id}/apply`
- `/api/tools/review-items/{id}/apply-normalization`
- `/api/tools/normalization-library`
- `/api/tools/normalization-rule-suggestions`
- `/api/tools/normalization-llm-tasks/{taskId}`
- `/api/tools/purchase-records/search`
MCP tools：

- `import_file`
- `record_purchase`
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
java -jar target/family-repurchase-agent.jar
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
### 归一化复核

```powershell
curl -X POST "http://localhost:8080/api/tools/review-items/12/apply-normalization" `
  -H "Content-Type: application/json" `
  -d "{\"action\":\"confirm\",\"normalizedName\":\"沐浴露\",\"targetUnit\":\"L\",\"includeInBaseline\":true,\"note\":\"确认该商品归一化为沐浴露\"}"
```

### 查询归一化规则库

```powershell
curl -X GET "http://localhost:8080/api/tools/normalization-library"
```

### LLM 规则维护建议

```powershell
curl -X POST "http://localhost:8080/api/tools/normalization-rule-suggestions" `
  -H "Content-Type: application/json" `
  -d "{\"batchId\":1,\"candidateMode\":\"legacy_fallback\",\"limit\":100,\"apply\":false}"
```

### 查询归一化 LLM 任务

```powershell
curl -X GET "http://localhost:8080/api/tools/normalization-llm-tasks/1"
```

### 写入归一化规则库

```powershell
curl -X POST "http://localhost:8080/api/tools/normalization-library" `
  -H "Content-Type: application/json" `
  -d "{\"action\":\"add_keyword\",\"ruleCode\":\"cat_litter\",\"keyword\":\"豆腐猫砂\",\"matchType\":\"include\",\"priority\":90}"
```

### 手动 / 自然语言购买记录录入

```powershell
curl -X POST "http://localhost:8080/api/tools/record-purchase" `
  -H "Content-Type: application/json" `
  -d "{\"dryRun\":true,\"records\":[{\"productName\":\"猫砂\",\"price\":178.65,\"quantity\":20,\"unit\":\"kg\",\"platform\":\"jd\",\"purchaseDate\":\"2026-06-04 20:30:00\",\"sku\":\"2.5kg*8\",\"sourceText\":\"我在京东买了猫砂2.5kg*8，花了178.65元\"}]}"
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

### 搜索原始购买记录
```powershell
curl -X POST "http://localhost:8080/api/tools/purchase-records/search" ` 
  -H "Content-Type: application/json" ` 
  -d "{\"keyword\":\"猫砂\",\"limit\":10}"
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
java -jar target/family-repurchase-agent.jar import examples/sample_orders.csv --owner=jtxw

java -jar target/family-repurchase-agent.jar price "猫砂" --price=89 --quantity=12 --unit=kg

java -jar target/family-repurchase-agent.jar report --month=2026-05
```

---

## MCP / Agent 集成

Family Repurchase Agent 通过 Java MCP stdio Server 暴露工具能力。Claude Code、Codex、OpenClaw 等 Agent Host 应通过 MCP 连接本项目，而不是直接访问数据库或绕过后端业务规则。

当前 MCP tools：

| Tool | 作用 | 典型问题 |
|---|---|---|
| `import_file` | 导入本地 CSV / Excel 订单文件 | “导入 examples/sample_orders.csv” |
| `record_purchase` | 录入手动购买记录或自然语言抽取后的结构化购买记录 | “记录一下，我在京东买了猫砂2.5kg*8，花了178.65元” |
| `compare_price` | 基于当前价格和历史样本判断是否值得买 | “猫砂 10.3 元 5kg 值得买吗？” |
| `get_price_baseline` | 查询某个复购品的历史价格基准线 | “查一下猫砂历史最低价 / 平均价” |
| `generate_report` | 生成指定月份的本地 Markdown 价格报告 | “生成 2026-05 的复购品报告” |

工具选择建议：

| 用户意图 | 推荐 MCP tool |
|---|---|
| 导入订单文件 | `import_file` |
| 记录一笔购买记录 / 从自然语言补充历史样本 | `record_purchase` |
| 判断当前价格是否值得买 | `compare_price` |
| 查询历史最低价 / 中位价 / 平均价 / 价格基准线 | `get_price_baseline` |
| 生成月度报告 | `generate_report` |

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
- 自然语言录入：用于补足京东、拼多多、线下超市等不方便导出订单的平台。LLM 只负责从自然语言中抽取结构化字段，后端负责最终校验和入库决策。
- 归一化规则闭环：系统不会让未确认的低置信归一化结果直接进入价格基准。人工复核用于修正购买记录；长期知识通过 `normalization_rules` 和 `normalization_rule_keywords` 维护，避免规则膨胀和样本污染。
- LLM 辅助规则维护而非自动入库：LLM 只负责从历史购买记录中发现规则库缺口，并建议新增规则或 include / exclude keyword。是否写入规则库由 `apply` 控制，写入前仍经过本地校验和 `NormalizationLibraryService`。
- 后端确定性收敛标准品类与单位：LLM 可以辅助理解复杂商品标题，但最终 normalizedName 和 targetUnit 仍由后端规则收敛。这样可以避免同类商品被拆成多个价格基准，也避免把规格值或不稳定包装单位误当成统计单位。
---

## License

This project is licensed under the Apache License 2.0.

This repository is intended for learning, portfolio demonstration, and technical discussion.
Demo data is synthetic or anonymized. Do not commit real personal consumption records,
real order exports, credentials, tokens, SSH keys, or production configuration files.
