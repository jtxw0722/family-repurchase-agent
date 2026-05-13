---
name: family-consumption-agent
description: Use this skill when the user wants to analyze local household purchase records, compare consumable prices, generate reports, or review abnormal purchase records.
---

# Family Consumption Agent Skill

使用本项目提供的 REST Tool API 或 CLI 完成家庭消费分析。

禁止：

- 登录电商平台；
- 读取 Cookie；
- 爬虫；
- 自动下单；
- 上传真实订单数据。

正式统计只纳入 include + unique 记录。遇到实付金额为 0、规格不明确、疑似重复时，应创建复核项，而不是直接纳入统计。
