-- Spec: "moved, not spent". Until now spentByCategory summed every negative amount in
-- every category on every account, so a transfer between the owner's own accounts (a
-- ФОП account paying its own black card, the black card paying the yellow card) counted
-- as spending twice, once leaving each account. A category marked here is excluded from
-- the honest total, the donut and the threshold notifier — its own transactions still show
-- up on /transactions and its own dashboard card, just outside the counted figure. Default
-- true (every existing category keeps counting) so this is invisible until the owner
-- opts a category out. No semicolons above this line: runMigrations splits each file on
-- that character, so one inside a comment silently truncates the statement.
ALTER TABLE categories ADD COLUMN counts_as_spending INTEGER NOT NULL DEFAULT 1;
