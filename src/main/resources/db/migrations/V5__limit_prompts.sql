-- One open row per "which amount?" question asked by /limit. Keyed on the id of the
-- message the bot asked with, because that id is what a reply carries back to us. No chat
-- column and no expiry: an open dialog is closed when the bot is re-paired into another
-- chat, so an open row always belongs to the chat the bot is talking to right now. No
-- semicolons in this comment -- runMigrations splits the whole file on that character, so
-- one inside a comment truncates the statement and fails with an opaque JDBC error
--
-- category_id carries no foreign key on purpose. Cascading the delete would take the open
-- question away with the category, and the person who had already been asked for an amount
-- would reply into silence. The row outlives the category so the answer can be "that
-- category no longer exists" instead of nothing at all
CREATE TABLE limit_prompts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    category_id INTEGER NOT NULL,
    keyboard_message_id INTEGER,
    reply_message_id INTEGER,
    created_at INTEGER NOT NULL,
    resolved_at INTEGER
);
CREATE UNIQUE INDEX limit_prompts_open_reply ON limit_prompts(reply_message_id) WHERE resolved_at IS NULL;
