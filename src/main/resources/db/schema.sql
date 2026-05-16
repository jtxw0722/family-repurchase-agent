CREATE TABLE IF NOT EXISTS raw_import_batches (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_file TEXT NOT NULL,
    status TEXT NOT NULL,
    total_count INTEGER NOT NULL DEFAULT 0,
    imported_count INTEGER NOT NULL DEFAULT 0,
    review_count INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS purchase_records (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    batch_id INTEGER,
    order_time TEXT,
    platform TEXT,
    owner TEXT,
    product_name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    sku TEXT,
    category TEXT,
    sub_category TEXT,
    quantity REAL,
    unit TEXT,
    total_amount REAL,
    unit_price REAL,
    currency TEXT DEFAULT 'CNY',
    decision TEXT DEFAULT 'include',
    is_duplicate INTEGER DEFAULT 0,
    dedupe_status TEXT DEFAULT 'unique',
    source_file TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS product_aliases (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    alias TEXT NOT NULL UNIQUE,
    normalized_name TEXT NOT NULL,
    category TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS review_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    record_id INTEGER,
    reason_code TEXT NOT NULL,
    reason_message TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    review_decision TEXT,
    review_note TEXT,
    created_at TEXT NOT NULL,
    resolved_at TEXT
);

CREATE TABLE IF NOT EXISTS agent_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_type TEXT NOT NULL,
    tool_name TEXT,
    input_summary TEXT,
    output_summary TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS user_preferences (
    pref_key TEXT PRIMARY KEY,
    pref_value TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_purchase_normalized_name ON purchase_records(normalized_name);
CREATE INDEX IF NOT EXISTS idx_purchase_order_time ON purchase_records(order_time);
CREATE INDEX IF NOT EXISTS idx_review_status ON review_items(status);
