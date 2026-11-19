-- 只扩展库存计算数据，不改购买、价格或商品归一化。由迁移入口逐列检测后执行。
ALTER TABLE inventory_items ADD COLUMN anchor_quantity TEXT;
ALTER TABLE inventory_items ADD COLUMN anchor_at TEXT;
ALTER TABLE inventory_items ADD COLUMN history_rebuild_required INTEGER NOT NULL DEFAULT 0;
ALTER TABLE inventory_items ADD COLUMN timeline_version INTEGER NOT NULL DEFAULT 0;
-- 仅保留重放需要的入库数量与日期；不包含批次、成本、仓库等字段。
CREATE TABLE IF NOT EXISTS inventory_receipts (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 inventory_item_id INTEGER NOT NULL REFERENCES inventory_items(id),
 received_date TEXT NOT NULL,
 quantity TEXT NOT NULL,
 request_key TEXT NOT NULL UNIQUE,
 created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_inventory_receipts_item_date ON inventory_receipts(inventory_item_id,received_date);
CREATE TABLE IF NOT EXISTS inventory_consumption_rates (
 inventory_item_id INTEGER NOT NULL REFERENCES inventory_items(id),
 effective_date TEXT NOT NULL,
 daily_rate TEXT,
 PRIMARY KEY(inventory_item_id,effective_date)
);
