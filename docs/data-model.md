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
