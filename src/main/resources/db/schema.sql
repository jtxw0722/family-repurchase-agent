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
    product_amount REAL,
    paid_amount REAL,
    shipping_fee REAL,
    amount_source TEXT DEFAULT 'paid_amount',
    unit_price REAL,
    currency TEXT DEFAULT 'CNY',
    decision TEXT DEFAULT 'include',
    is_duplicate INTEGER DEFAULT 0,
    dedupe_status TEXT DEFAULT 'unique',
    source_file TEXT,
    shop_name TEXT,
    note TEXT,
    source_text TEXT,
    normalization_rule TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS product_aliases (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    alias TEXT NOT NULL,
    alias_key TEXT NOT NULL UNIQUE,
    normalized_name TEXT NOT NULL,
    target_unit TEXT,
    category TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS product_negative_aliases (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    alias TEXT NOT NULL,
    alias_key TEXT NOT NULL UNIQUE,
    rejected_normalized_name TEXT NOT NULL,
    reason TEXT,
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

CREATE TABLE IF NOT EXISTS normalization_suggestions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    batch_id INTEGER,
    raw_product_name TEXT NOT NULL,
    sku TEXT,
    alias_key TEXT NOT NULL,
    action TEXT NOT NULL,
    suggested_normalized_name TEXT,
    rejected_normalized_name TEXT,
    product_type TEXT,
    target_unit TEXT,
    unit_family TEXT,
    confidence REAL NOT NULL,
    review_required INTEGER NOT NULL DEFAULT 1,
    reason TEXT,
    evidence_json TEXT,
    llm_provider TEXT,
    llm_model TEXT,
    prompt_version TEXT,
    status TEXT NOT NULL DEFAULT 'pending',
    created_at TEXT NOT NULL,
    reviewed_at TEXT
);

CREATE TABLE IF NOT EXISTS normalization_analysis_tasks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    batch_id INTEGER NOT NULL,
    status TEXT NOT NULL,
    limit_count INTEGER NOT NULL DEFAULT 100,
    force_reanalyze INTEGER NOT NULL DEFAULT 0,
    include_keywords_json TEXT NOT NULL DEFAULT '[]',
    exclude_keywords_json TEXT NOT NULL DEFAULT '[]',
    only_failed INTEGER NOT NULL DEFAULT 0,
    candidate_count INTEGER NOT NULL DEFAULT 0,
    analyzed_count INTEGER NOT NULL DEFAULT 0,
    auto_excluded_count INTEGER NOT NULL DEFAULT 0,
    pending_batch_approval_count INTEGER NOT NULL DEFAULT 0,
    pending_review_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    current_batch_index INTEGER NOT NULL DEFAULT 0,
    total_batch_count INTEGER NOT NULL DEFAULT 0,
    message TEXT,
    error_message TEXT,
    created_at TEXT NOT NULL,
    started_at TEXT,
    finished_at TEXT,
    updated_at TEXT NOT NULL
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
CREATE INDEX IF NOT EXISTS idx_normalization_suggestions_batch_id ON normalization_suggestions(batch_id);
CREATE INDEX IF NOT EXISTS idx_normalization_suggestions_alias_key ON normalization_suggestions(alias_key);
CREATE INDEX IF NOT EXISTS idx_normalization_suggestions_status ON normalization_suggestions(status);
CREATE INDEX IF NOT EXISTS idx_normalization_analysis_tasks_status ON normalization_analysis_tasks(status);
CREATE INDEX IF NOT EXISTS idx_normalization_analysis_tasks_batch_id ON normalization_analysis_tasks(batch_id);
