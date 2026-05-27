# Family Consumption Agent

[![CI](https://github.com/jtxw0722/family-consumption-agent/actions/workflows/ci.yml/badge.svg)](https://github.com/jtxw0722/family-consumption-agent/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-17%2B-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen)
![Release](https://img.shields.io/github/v/release/jtxw0722/family-consumption-agent?display_name=tag)

本地优先的家庭消耗品复购分析工具。

它不是普通记账应用，重点是把本地订单数据导入后，计算单位价格、对比历史价格、标记异常记录，并生成月度消费报告。

当前版本是一个 Spring Boot 后端 MVP，提供 REST Tool API 和 CLI 辅助入口。后续可以作为 Tool Server 接入 OpenClaw / Codex / Claude Code / Spring AI。

## 功能

- CSV / Excel 订单导入
- SQLite 本地存储
- 商品名称归一化
- 单位价格计算
- 当前价格与历史价格对比
- 实付为 0 等异常记录进入待复核列表
- Markdown 月度报告生成
- REST Tool API
- CLI 辅助命令

## 技术栈

| 类型 | 选型 |
|---|---|
| 语言 | Java 17+ |
| 框架 | Spring Boot 3.x |
| 数据库 | SQLite |
| 文件导入 | Apache Commons CSV / Apache POI |
| 测试 | JUnit 5 |
| 构建 | Maven |

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
java -jar target/family-consumption-agent-0.2.0-SNAPSHOT.jar
```

服务启动后会自动准备本地目录和 SQLite 数据库：

```text
data/family-consumption.sqlite
data/inbox/
reports/
```

默认服务地址：

```text
http://localhost:8080
```

## REST API

接口文档：

```text
http://localhost:8080/swagger-ui.html
```

导入订单：

```powershell
curl -X POST "http://localhost:8080/api/tools/import-file" `
  -H "Content-Type: application/json" `
  -d "{\"filePath\":\"examples/sample_orders.csv\",\"owner\":\"jtxw\"}"
```

`owner` 为可选参数。导入时会按以下顺序确定订单归属人：

1. 请求体中的 `owner`
2. CSV 中的 `owner` 字段
3. 文件名后缀，例如 `订单数据-jtxw.csv`

最终入库前会统一为大写，例如 `jtxw` 和 `JTXW` 都会保存为 `JTXW`。

当前支持 CSV 和 Excel 两种文件格式。表头支持：

- 项目标准模板：`order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,currency`
- 中文订单导出模板：`订单提交时间、订单状态、店铺名称、商品名称、商品链接、型号款式、商品数量、商品金额、实付金额、运费`

判断价格：

```powershell
curl -X POST "http://localhost:8080/api/tools/compare-price" `
  -H "Content-Type: application/json" `
  -d "{\"productName\":\"猫砂\",\"price\":89,\"quantity\":12,\"unit\":\"kg\"}"
```

生成报告：

```powershell
curl -X POST "http://localhost:8080/api/tools/generate-report" `
  -H "Content-Type: application/json" `
  -d "{\"month\":\"2026-05\"}"
```

查看待复核记录：

```powershell
curl "http://localhost:8080/api/tools/review-items"
```

应用复核结果：

```powershell
curl -X POST "http://localhost:8080/api/tools/review-items/1/apply" `
  -H "Content-Type: application/json" `
  -d "{\"action\":\"exclude\",\"note\":\"试用装，不纳入正式统计\"}"
```

## CLI

```bash
java -jar target/family-consumption-agent-0.2.0-SNAPSHOT.jar import examples/sample_orders.csv --owner=jtxw

java -jar target/family-consumption-agent-0.2.0-SNAPSHOT.jar price "猫砂" --price=89 --quantity=12 --unit=kg

java -jar target/family-consumption-agent-0.2.0-SNAPSHOT.jar report --month=2026-05

java -jar target/family-consumption-agent-0.2.0-SNAPSHOT.jar review list

java -jar target/family-consumption-agent-0.2.0-SNAPSHOT.jar review apply 1 --action=exclude --note=试用装
```

导入中文订单导出 CSV / Excel 时，如果文件内没有 `owner` 字段，可以使用参数指定：

```bash
java -jar target/family-consumption-agent-0.2.0-SNAPSHOT.jar import 订单数据.csv --owner=jtxw
java -jar target/family-consumption-agent-0.2.0-SNAPSHOT.jar import 订单数据.xlsx --owner=jtxw
```

也可以通过文件名指定：

```bash
java -jar target/family-consumption-agent-0.2.0-SNAPSHOT.jar import 订单数据-jtxw.csv
java -jar target/family-consumption-agent-0.2.0-SNAPSHOT.jar import 订单数据-jtxw.xlsx
```

## 项目结构

```text
src/main/java/com/jtxw/familyagent/
├── application/        # 应用服务
├── domain/             # 领域模型与规则
├── infrastructure/     # 数据库、导入、报告输出
└── interfaces/         # REST API 和 CLI

src/main/resources/
├── application.yml
└── db/schema.sql

examples/               # 合成示例数据
docs/                   # 项目文档
adapters/               # Agent Host 适配示例
evals/                  # 评测用例
```

## 数据口径

默认只统计可信记录：

```text
decision = include
is_duplicate = 0
dedupe_status = unique
```

金额口径：

- 普通记录默认使用 `实付金额` 作为统计金额
- 当 `实付金额 = 0` 且 `商品金额 > 0` 时，按 `商品金额 + 运费` 折算统计金额
- 折算记录会进入待复核，原因编码为 `PAYMENT_ADJUSTMENT`

以下记录会优先进入待复核：

- 实付金额为 0
- 数量或规格无法解析
- 疑似重复订单
- 赠品、试用、售后补发
- 购物金、礼品卡、组合支付等需要折算的记录

## 隐私边界

项目默认本地运行，订单数据保存在本机 SQLite 中。

不做：

- 登录电商平台
- 读取 Cookie 或浏览器会话
- 爬取购物网站
- 自动下单
- 上传真实订单数据

`examples/` 只放合成示例数据，不提交真实订单、手机号、地址、订单号或支付流水。

## Roadmap

### v0.1

- [x] Spring Boot 项目骨架
- [x] SQLite 自动初始化
- [x] CSV 导入
- [x] 单位价格计算
- [x] 价格对比
- [x] Markdown 报告
- [x] 待复核记录
- [x] REST Tool API
- [x] CLI 辅助入口

### v0.2

- [x] 人工复核 apply 流程
- [x] 重复订单检测
- [x] Excel 导入
- [x] 购物金 / 礼品卡折算
- [x] 报告模板增强

### v0.3+

- [ ] MCP Server
- [ ] OpenClaw Plugin 原型
- [ ] Codex Skill 示例
- [ ] Claude Code Subagent 示例
- [ ] Spring AI Tool Calling

## License

MIT
