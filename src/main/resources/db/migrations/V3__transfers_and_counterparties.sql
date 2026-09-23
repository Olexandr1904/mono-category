-- MCC 4829 and its kind are conduits, not merchant types: the code says "money left by
-- transfer" and nothing about why. Such codes are excluded from the MCC layer entirely,
-- so their transactions arrive uncategorized and the bot asks about each one.
CREATE TABLE conduit_mcc (
    mcc  INTEGER PRIMARY KEY,
    note TEXT NOT NULL DEFAULT ''
);

-- The recipient layer. counterparty_key is the primary key, so "one recipient, one
-- category" is enforced by the schema exactly as "one MCC, one category" already is.
CREATE TABLE category_counterparty (
    counterparty_key TEXT PRIMARY KEY,
    category_id      INTEGER NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    display_name     TEXT NOT NULL DEFAULT '',
    created_at       INTEGER NOT NULL
);
CREATE INDEX idx_category_counterparty_category ON category_counterparty(category_id);

ALTER TABLE transactions ADD COLUMN counterparty_key TEXT;
ALTER TABLE transactions ADD COLUMN counterparty_source TEXT;
CREATE INDEX idx_transactions_counterparty ON transactions(counterparty_key);

ALTER TABLE mcc_prompts ADD COLUMN kind TEXT NOT NULL DEFAULT 'mcc';
ALTER TABLE mcc_prompts ADD COLUMN reply_message_id INTEGER;

-- One open question per code is right for an unknown merchant code: five purchases in a
-- new shop should produce one message. Every transfer carries the SAME code, so under the
-- old index only the first transfer could ever have an open question and the bot would go
-- silent forever. Transfers are unique per transaction instead.
DROP INDEX idx_mcc_prompts_open;
CREATE UNIQUE INDEX idx_mcc_prompts_open_mcc
    ON mcc_prompts(mcc) WHERE resolved_at IS NULL AND kind = 'mcc';
CREATE UNIQUE INDEX idx_mcc_prompts_open_txn
    ON mcc_prompts(transaction_id) WHERE resolved_at IS NULL AND kind = 'transfer';
