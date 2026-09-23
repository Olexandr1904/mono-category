-- One-off repair. The category form used to render four identical, unlabelled text boxes,
-- so the monthly limit was typed into the emoji field: rows held emoji '🍽25000', and
-- Category.label ("$emoji $name") put it in front of the name in every Telegram button,
-- dashboard row and dropdown. Digits are not emoji, so removing them is safe and total.
-- app.web.sanitizeEmoji applies the same rule to everything saved from now on.
UPDATE categories SET emoji = TRIM(
    REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
    REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
        emoji,
    '0', ''), '1', ''), '2', ''), '3', ''), '4', ''),
    '5', ''), '6', ''), '7', ''), '8', ''), '9', '')
)
WHERE emoji GLOB '*[0-9]*'
