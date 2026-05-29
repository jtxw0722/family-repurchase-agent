# Family Repurchase Agent

[![CI](https://github.com/jtxw0722/family-repurchase-agent/actions/workflows/ci.yml/badge.svg)](https://github.com/jtxw0722/family-repurchase-agent/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-17%2B-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen)
![Release](https://img.shields.io/github/v/release/jtxw0722/family-repurchase-agent?display_name=tag)

> 本地优先的家庭复购品价格决策 Agent，基于 Java、Spring Boot、SQLite、MCP Server 和 OpenClaw Plugin 构建。

**Family Repurchase Agent** 是一个面向家庭高频复购品的价格决策 Agent。

它关注的不是“我花了多少钱”，而是：

> **这个价格现在值不值得买？**

针对猫砂、纸巾、洗衣液等高频复购品，系统支持将不同规格、不同单位、不同包装形式的商品价格归一化为单位价格，并结合历史购买记录计算价格基准线，判断当前价格是否值得购买。同时通过 MCP Server 和 OpenClaw Plugin 将价格分析能力封装为 Agent 可调用工具。

---

## 项目动机

家庭复购品的购买决策并不适合只看订单总价。

同一类商品经常存在不同规格、不同单位、组合装、优惠券、赠品和平台价格差异。例如：

```text
10kg
20斤
5kg * 4袋
500ml * 12瓶
买二送一
第二件半价
```

对用户来说，真正有价值的问题是：

* 当前价格折算到标准单位后是多少？
* 它相对历史最低价、中位价、最近购买价处于什么区间？
* 这个价格是否值得现在购买？
* 是否适合囤货，还是应该等待更低价格？

Family Repurchase Agent 使用本地价格样本、单位价格归一化和历史价格基准线来回答这些问题，而不是让 LLM 直接凭经验生成结论。

---

## 示例

输入：

```text
猫砂 119.3 元 / 40kg
```

示例输出：

```text
单位价格：2.98 元/kg
历史中位价：4.20 元/kg
历史最低价：2.80 元/kg
样本数量：23
判断：当前价格低于历史中位价，接近历史低位，可以考虑补货
```

> 示例输出仅用于展示结果形态，实际判断取决于本地 SQLite 中的历史价格样本。

---

## 核心能力

### 1. 单位价格归一化

系统会将不同包装规格统一换算为标准单位价格，例如：

```text
元/kg
元/L
元/片
元/包
元/瓶
```

用于解决电商商品规格不统一导致的价格不可比问题。

---

### 2. 历史价格基准线

系统基于本地历史价格样本计算：

* 历史最低价
* 历史中位价
* 历史平均价
* 最近购买价
* 样本数量

价格判断基于这些可验证数据，而不是模型猜测。

---

### 3. 复购品维度建模

系统通过商品名称归一化和别名映射，将不同品牌、规格、平台下的相似商品映射到统一复购品维度。

例如：

```text
名创优品膨润土猫砂 10kg*4
MINISO 猫砂 40kg
膨润土猫砂组合装
```

可以归一到统一的复购品维度：

```text
normalizedName = 猫砂
standardUnit   = kg
```

---

### 4. 数据质量复核

对可能影响历史价格基准线的异常样本，系统会进入人工复核流程，例如：

* 实付金额为 0
* 疑似重复记录
* 数量或单位无法解析
* 赠品、试用装、售后补发
* 购物金、礼品卡、组合支付等需要折算的记录

只有可信记录才会进入正式统计口径，避免错误样本污染历史价格判断。

---

### 5. 可解释输出

每次价格判断都会返回计算公式、样本数量、历史基准线和证据样本，避免纯大模型生成造成不可验证结论。

示例输入：

```json
{
  "productName": "膨润土猫砂",
  "price": 10.3,
  "quantity": 5,
  "unit": "kg"
}
```

工具会先将当前价格换算为标准单位价格：

```text
10.3 / 5 = 2.06 元/kg
```

然后基于本地历史样本生成价格基准线：

```text
归一化商品：猫砂
历史样本数：11 条
历史最低价：2.84 元/kg
历史中位价：3.53 元/kg
历史平均价：6.46 元/kg
历史时间范围：2023-11-06 ~ 2025-11-09
判断结果：好价
置信度：medium
```

同时返回用于支撑判断的证据样本：

```text
historical_min:
  日期：2025-08-07
  商品：某品牌膨润土猫砂 4.5kg * 2
  金额：25.58 元
  规格：9 kg
  单位价格：2.84 元/kg

median_sample:
  日期：2024-01-15
  商品：某品牌矿石猫砂 4.5kg
  金额：15.90 元
  规格：4.5 kg
  单位价格：3.53 元/kg

latest:
  日期：2025-11-09
  商品：某品牌钠基矿猫砂 5kg * 8
  金额：119.30 元
  规格：40 kg
  单位价格：2.98 元/kg
```

如果存在无法参与统计的历史记录，工具也会给出排除原因：

```text
排除记录：1 条
原因：单位不一致，历史记录不是 kg，未参与本次价格判断。
```

因此，Agent 最终回复用户时，不是直接“猜测”当前价格是否划算，而是基于后端工具返回的单位价格、历史基准线和证据样本进行总结。


---

### 6. Agent 工具接口

项目通过以下方式暴露能力：

* REST Tool API
* CLI
* Java MCP stdio Server
* OpenClaw Plugin Prototype

Agent Host 负责自然语言理解、工具选择和用户交互；后端负责可测试、可复核的价格计算和数据处理。

---

## 当前状态

`v0.4.0` 是 Family Repurchase Agent 的定位与命名统一版本。

当前已实现：

* CSV / Excel 导入
* SQLite 本地存储
* 商品名称归一化
* 单位价格计算
* 当前价格与历史价格对比
* 重复记录检测
* 异常样本人工复核
* Markdown 复购品价格报告
* REST Tool API
* CLI 辅助命令
* Java MCP stdio Server
* OpenClaw Plugin Prototype

已规划但尚未实现：

* 自然语言价格样本录入
* `PurchaseCandidate` 候选态
* dry-run / confirm 写入流程
* 更完整的价格基准线计算器
* 价格样本批量导入与价格报告增强

---

## 架构

```text
CLI / REST / MCP / OpenClaw
        ↓
Spring Boot Tool API
        ↓
Application Service
        ↓
Domain Policy
        ↓
Repository
        ↓
SQLite
```

后端将确定性逻辑保留在 Java Application Service 和 Domain Policy 中。

MCP Server 和 OpenClaw Plugin 只是 adapter：它们负责协议适配、参数校验、路径安全检查和 HTTP 转发，不直接访问 SQLite，也不实现业务规则。

这种边界让 Agent 负责自然语言理解和工具选择，让后端负责可测试、可复核的价格计算。

---

## 技术栈

| 类型       | 选型                                                |
| -------- | ------------------------------------------------- |
| 语言       | Java 17+                                          |
| 框架       | Spring Boot 3.x                                   |
| 数据库      | SQLite                                            |
| 文件导入     | Apache Commons CSV / Apache POI                   |
| 测试       | JUnit 5                                           |
| 构建       | Maven                                             |
| Agent 集成 | Java MCP stdio Server / OpenClaw Plugin Prototype |

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

打包并启动：

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

## API 示例

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

当前 MCP tools：

* `import_file`
* `compare_price`
* `generate_report`

当前 OpenClaw Plugin tools：

* `family_repurchase_import_file`
* `family_repurchase_compare_price`
* `family_repurchase_generate_report`

当前 tool name 与 v0.4 后端保持兼容。后续版本会逐步迁移到更明确的价格样本语义：

* `record_price_sample`
* `import_price_samples`
* `compare_current_price`
* `generate_price_report`

适配器路径：

```text
adapters/mcp/family-repurchase-mcp-java-server/
adapters/openclaw/family-repurchase-openclaw-plugin/
```
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
├── openclaw/           # OpenClaw Plugin Prototype
├── claude-code/        # Claude Code adapter
└── codex/              # Codex Skill adapter

examples/               # 合成示例数据
evals/                  # 评测用例
```

---
适配器文档：

* [MCP Java Server](adapters/mcp/family-repurchase-mcp-java-server/README.md)
* [OpenClaw Plugin Prototype](adapters/openclaw/family-repurchase-openclaw-plugin/README.md)

---

## 隐私与安全

Family Repurchase Agent 默认本地运行。

- 数据默认保存在本机 SQLite
- 不登录电商平台
- 不读取 Cookie 或浏览器会话
- 不爬取购物网站
- 不自动下单
- 不上传真实订单数据到云端
- `examples/` 仅使用合成示例数据

---

## Roadmap

* `v0.5`: `record_price_sample`

    * 自然语言输入
    * 槽位提取
    * dry-run `PurchaseCandidate`
    * 用户确认后写入正式样本库

* `v0.6`: `compare_current_price`

    * 更强的单位换算模型
    * 历史最低价 / 中位价 / 平均价
    * warning 模型
    * 可解释购买建议

* `v0.7`: `import_price_samples` / `generate_price_report`

    * 批量价格样本导入
    * 样本质量摘要
    * 面向价格决策的报告模板

---

## License

MIT
