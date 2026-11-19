-- 由InventoryMigration逐列检查后执行，兼容SQLite，无历史数据回填。
ALTER TABLE inventory_items ADD COLUMN last_received_date TEXT;
ALTER TABLE inventory_items ADD COLUMN last_receipt_note TEXT;
