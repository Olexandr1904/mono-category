CREATE TABLE accounts (
    id            TEXT PRIMARY KEY,
    masked_pan    TEXT NOT NULL DEFAULT '',
    type          TEXT NOT NULL DEFAULT '',
    currency_code INTEGER NOT NULL,
    balance_minor INTEGER NOT NULL DEFAULT 0,
    active        INTEGER NOT NULL DEFAULT 1,
    updated_at    INTEGER NOT NULL
);

CREATE TABLE categories (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    name                TEXT NOT NULL,
    emoji               TEXT NOT NULL DEFAULT '',
    monthly_limit_minor INTEGER NOT NULL DEFAULT 0,
    threshold_pct       INTEGER NOT NULL DEFAULT 80,
    notify_warning      INTEGER NOT NULL DEFAULT 1,
    notify_exceeded     INTEGER NOT NULL DEFAULT 1,
    enabled             INTEGER NOT NULL DEFAULT 1,
    position            INTEGER NOT NULL DEFAULT 0,
    created_at          INTEGER NOT NULL
);

CREATE TABLE category_mcc (
    mcc         INTEGER PRIMARY KEY,
    category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE CASCADE
);
CREATE INDEX idx_category_mcc_category ON category_mcc(category_id);

CREATE TABLE transactions (
    id                   TEXT PRIMARY KEY,
    account_id           TEXT NOT NULL,
    occurred_at          INTEGER NOT NULL,
    month                TEXT NOT NULL,
    amount_minor         INTEGER NOT NULL,
    currency_code        INTEGER NOT NULL,
    description          TEXT NOT NULL DEFAULT '',
    mcc                  INTEGER,
    original_mcc         INTEGER,
    hold                 INTEGER NOT NULL DEFAULT 0,
    category_id          INTEGER REFERENCES categories(id) ON DELETE SET NULL,
    manually_categorized INTEGER NOT NULL DEFAULT 0,
    raw_json             TEXT NOT NULL DEFAULT '',
    created_at           INTEGER NOT NULL
);
CREATE INDEX idx_transactions_month_category ON transactions(month, category_id);
CREATE INDEX idx_transactions_occurred ON transactions(occurred_at);

CREATE TABLE webhook_events (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    payload      TEXT NOT NULL,
    received_at  INTEGER NOT NULL,
    processed_at INTEGER
);
CREATE INDEX idx_webhook_events_unprocessed ON webhook_events(processed_at);

CREATE TABLE notification_events (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    category_id INTEGER NOT NULL,
    month       TEXT NOT NULL,
    threshold   INTEGER NOT NULL,
    notified_at INTEGER NOT NULL,
    UNIQUE(category_id, month, threshold)
);

CREATE TABLE mcc_prompts (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    mcc            INTEGER NOT NULL,
    transaction_id TEXT NOT NULL,
    message_id     INTEGER,
    created_at     INTEGER NOT NULL,
    resolved_at    INTEGER
);
CREATE UNIQUE INDEX idx_mcc_prompts_open ON mcc_prompts(mcc) WHERE resolved_at IS NULL;

CREATE TABLE settings (
    key       TEXT PRIMARY KEY,
    value     TEXT NOT NULL,
    encrypted INTEGER NOT NULL DEFAULT 0
);
