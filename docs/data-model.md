# 数据模型

核心表：

- `raw_import_batches`：导入批次；
- `purchase_records`：标准化购买记录；
- `product_aliases`：商品别名；
- `review_items`：人工复核项；
- `agent_events`：Agent 执行事件；
- `user_preferences`：用户偏好。

正式统计口径：

```text
decision = include
is_duplicate = false
dedupe_status = unique
```

复核项状态：

```text
pending  = 等待人工处理
resolved = 已应用人工复核结果
```

复核结果会写入 `review_decision` 和 `review_note`，并同步更新关联的 `purchase_records.decision`：

```text
include = 纳入正式统计
exclude = 排除正式统计
```
