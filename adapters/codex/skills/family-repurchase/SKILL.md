---
name: family-repurchase-mcp
description: 当用户需要通过 Codex 使用 Family Repurchase Agent MCP tools 进行复购品价格判断、订单文件导入或价格报告生成时使用。
---

# Family Repurchase Agent MCP Skill

本 Skill 用于指导 Codex 在已配置 Family Repurchase MCP Server 的前提下，通过 MCP tools 使用 Family Repurchase Agent。

## 使用场景

当用户询问以下问题时，应优先使用 Family Repurchase MCP tools：

* 某个复购品当前价格是否值得买
* 当前价格是否低于历史价格
* 当前价格是否适合囤货
* 如何导入本地 CSV / Excel 订单文件
* 如何生成复购品价格报告

## 可用 MCP tools

* `import_file`：导入本地 CSV / Excel 订单文件
* `compare_price`：比较当前单位价格与本地历史购买记录
* `generate_report`：生成指定月份的复购品价格报告

## 使用原则

不要直接凭模型常识判断价格是否划算。

价格结论应基于 MCP tool 返回的结构化结果，包括：

* 当前单位价格
* 计算公式
* 历史最低价
* 历史中位价
* 历史平均价
* 样本数量
* evidence
* warnings
* excludedReasons

## 工具选择

用户询问“这个价格值不值得买”“是否好价”“是否适合囤货”时，优先调用：

```text
compare_price
```

用户要求导入本地订单文件时，优先调用：

```text
import_file
```

用户要求生成某个月的复购品价格报告时，优先调用：

```text
generate_report
```

## 边界

Codex 应通过已配置的 Family Repurchase MCP Server 使用项目能力。

不要绕过 MCP Server 直接访问 SQLite、Repository 或 Domain Service。

不要绕过 Spring Boot 后端业务规则自行计算价格结论。

如果当前 Codex 会话尚未配置 Family Repurchase MCP Server，应先提示用户完成 MCP Server 配置，不要编造未确认的配置字段。
