---
name: family-repurchase-java
description: Use this skill when maintaining the Java Spring Boot Family Repurchase Agent project.
---

# Family Repurchase Java Skill

维护本项目时：

1. 修改业务逻辑前先阅读 `docs/data-model.md`。
2. 保持 core 逻辑和 adapters 解耦。
3. 修改价格判断、单位换算、导入逻辑后必须补测试。
4. 不要提交真实订单、SQLite 数据库或生成报告。
5. 面向用户的 README、docs、CLI 输出默认中文。
