-- 独立库存扩展，仅增加表，不更改购买记录或归一化规则。
CREATE TABLE IF NOT EXISTS inventory_items (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 normalized_product_id INTEGER NOT NULL UNIQUE REFERENCES normalization_rules(id),
 current_quantity TEXT,
 base_unit TEXT NOT NULL,
 quantity_recorded_at TEXT NOT NULL,
 tracking_enabled INTEGER NOT NULL DEFAULT 1,
 created_at TEXT NOT NULL,
 updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS replenishment_settings (
 inventory_item_id INTEGER PRIMARY KEY REFERENCES inventory_items(id),
 manual_reference_quantity TEXT,
 manual_reference_unit TEXT,
 manual_reference_days TEXT,
 manual_daily_rate TEXT,
 effective_source TEXT NOT NULL DEFAULT 'AUTO',
 reminder_lead_days INTEGER NOT NULL DEFAULT 3,
 snoozed_until TEXT,
 updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS replenishment_predictions (
 inventory_item_id INTEGER PRIMARY KEY REFERENCES inventory_items(id),
 auto_daily_rate TEXT,
 auto_sample_count INTEGER NOT NULL DEFAULT 0,
 purchase_record_count INTEGER NOT NULL DEFAULT 0,
 sample_label TEXT NOT NULL,
 warnings TEXT NOT NULL DEFAULT '',
 calculated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS inventory_purchase_links (
 purchase_record_id INTEGER PRIMARY KEY,
 inventory_item_id INTEGER NOT NULL REFERENCES inventory_items(id),
 added_quantity TEXT NOT NULL,
 created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS inventory_requests (
 request_key TEXT PRIMARY KEY,
 fingerprint TEXT NOT NULL,
 inventory_item_id INTEGER,
 created_at TEXT NOT NULL
);
