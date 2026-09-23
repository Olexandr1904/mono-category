# Transfers, counterparty rules and creating a category from the bot — design

Date: 2026-08-31
Status: approved section by section, awaiting proofread

Extends [2026-08-18-monobank-budget-tracker-design.md](2026-08-18-monobank-budget-tracker-design.md).
Where the two documents disagree, this one applies.

## 1. Goal

Clear out the «Різне» category. In August 2026, 56 outgoing transactions with
MCC 4829 totalling 1 200 000 ₴ had settled there — transfers to other people's
cards. The code 4829 carries no meaning: a tutor, the rent, a sole-trader
contractor and a birthday present all arrive under it alike. As long as the
code is bound to one category, that money looks sorted when it is not.

Two capabilities are needed for this:

1. **Create a category straight from the bot.** The button layout gains
   «➕ Нова категорія»; the bot asks for a name, creates the category and applies
   the same choice to it.
2. **Ask about each transfer separately** and, when the counterparty could be
   identified, set up a rule "this counterparty → this category" that picks up
   both the past and the future transactions of the same counterparty.

Incoming transactions take no part in any of it: the tool counts spending, not
income (a decision made earlier; the 2026-08-18 specification's §7 is
superseded).

## 2. What breaks

The application rests on the rule "one MCC — one category". That is not a
convention but a schema constraint: `category_mcc.mcc` is a primary key.
`recategorize()` rewrites the whole history by that map, except rows with
`manually_categorized`.

Sorting transfers one by one means that code 4829 will hold transactions from
a dozen different categories. To the current model that is an anomaly the next
recomputation is obliged to correct.

The cause is that we treated two different kinds of code as one:

- **Merchant codes** (5411 groceries, 5812 restaurants) — the code is the
  meaning. A rule across the whole history works and must stay.
- **Conduit codes** (4829 transfer) — the code says only "money left by
  transfer". The counterparty decides the category.

## 3. Model: three layers with precedence

A transaction's category is decided by the first rule that fires:

| Layer | Rule | Key | State |
|---|---|---|---|
| 1 | A manual decision about the transaction | transaction id | exists — `manually_categorized` |
| 2 | A counterparty rule | `counterparty_key` | new |
| 3 | An MCC binding | `mcc`, if the code is not a conduit code | exists — `category_mcc` |
| — | otherwise «Без категорії» | | |

This generalizes what exists rather than replacing it: today's `category_mcc`
is the third layer, `manually_categorized` the first. The "one MCC — one
category" constraint stays in force for the third layer. Conduit codes simply
take no part in it.

A conflict between layers is resolved in favour of the more specific: if a
transaction has both a counterparty rule and an MCC binding, the counterparty
wins.

### The conduit-code invariant

**A code cannot be both a conduit code and bound to a category.** Marking a
code as conduit removes its binding; a conduit code cannot be entered into a
category's list.

This is held in code, not by discipline: a violation would show up as "the bot
is silent about transfers", because the third layer would keep filing them and
they would never be uncategorized.

### What triggers a recomputation

To the existing triggers (a change to an MCC binding, deleting a category) are
added: creating and removing a counterparty rule, and marking and unmarking a
conduit code. Everything goes through `IngestService` under the same mutex.

Deleting a category cascades to both its codes and its counterparty rules.

### Manual versus "by rule"

`manually_categorized` means "a person decided about this row" and makes the
row untouchable by recomputation. A row filed by a **counterparty rule** is not
considered manual — otherwise a change to the rule would not reach it.

Hence the behaviour when answering in the bot or on the web:

- the counterparty key is known → a rule is created, and the row is **not**
  marked manual;
- there is no key → a manual override of this one row only.

## 4. Schema: migration V3

`V2__strip_limits_from_emoji.sql` is already taken, so the new migration is the
third. The migration runner splits a file on `;` and would break on a trigger
body or a semicolon inside a string literal; V3 has neither, so the runner does
not need to change. This has to be checked by eye when writing the file, not
assumed.

```sql
CREATE TABLE conduit_mcc (
    mcc  INTEGER PRIMARY KEY,
    note TEXT NOT NULL DEFAULT ''
);

CREATE TABLE category_counterparty (
    counterparty_key TEXT PRIMARY KEY,
    category_id      INTEGER NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    display_name     TEXT NOT NULL DEFAULT '',
    created_at       INTEGER NOT NULL
);
CREATE INDEX idx_category_counterparty_category ON category_counterparty(category_id);

ALTER TABLE transactions ADD COLUMN counterparty_key    TEXT;
ALTER TABLE transactions ADD COLUMN counterparty_source TEXT;
CREATE INDEX idx_transactions_counterparty ON transactions(counterparty_key);

ALTER TABLE mcc_prompts ADD COLUMN kind              TEXT NOT NULL DEFAULT 'mcc';
ALTER TABLE mcc_prompts ADD COLUMN reply_message_id  INTEGER;

DROP INDEX idx_mcc_prompts_open;
CREATE UNIQUE INDEX idx_mcc_prompts_open_mcc
    ON mcc_prompts(mcc) WHERE resolved_at IS NULL AND kind = 'mcc';
CREATE UNIQUE INDEX idx_mcc_prompts_open_txn
    ON mcc_prompts(transaction_id) WHERE resolved_at IS NULL AND kind = 'transfer';
```

`counterparty_key` is the primary key of the rules table, so "one
counterparty — one category" is protected by the schema exactly as "one code —
one category" is. We extend the same discipline to the new layer rather than
introducing a second way of doing it.

### Why the key is stored on the transaction row

Deriving the key depends on the bank's raw response and on normalization
rules. Storing it makes recomputation and grouping an index lookup, and makes
the value inspectable: the database shows exactly whom the application took to
be the counterparty. The cost: if the derivation rule changes, old rows keep
their old keys. Acceptable, because we do not touch history anyway.

### Uniqueness of open questions

The old index gave "one open question per code". For an unfamiliar code that
is right: five purchases at a new shop produce one message. But every transfer
carries the same code, so for them uniqueness has to be per transaction —
otherwise `create()` would return `null` for every transfer but the first, and
the bot would fall silent for good.

Accordingly, `MccPromptRepository.openFor(mcc)` and `resolve(mcc)` gain
variants keyed by the question's id rather than by the code.

## 5. The bank's response and the counterparty key

### Store it whole

Today `raw_json` is a serialization of our own nine-field model, so
`counterName`, `counterIban`, `counterEdrpou`, `comment` and `receiptId` are
lost in parsing. We will store **the bank's response item verbatim**: parse it
into a `JsonElement`, store it as is, and derive the typed model from it.

Which field will be needed next is not known in advance, and history cannot be
recovered: the statement window is 31 days, at one request a minute per
endpoint. The item's shape is the same for the webhook (`data.statementItem`)
and for the statement, so the path is shared.

### The key-derivation ladder

Top to bottom, first match wins:

| Source | Key | Precision |
|---|---|---|
| `counterIban` | `iban:<IBAN upper-cased, no spaces>` | exact |
| `counterEdrpou` | `edrpou:<digits>` | exact |
| card mask from `description` | `card:<mask>` | exact |
| `counterName` | `name:<lower-cased, whitespace collapsed>` | probable |
| nothing recognised | `null` | — |

Exact sources give the right to link silently. A probable one only gives the
right to show a hint and ask: matching names are not proof.

**Shape not recognised — no key.** A false key is worse than a missing one: it
silently merges different people into one rule, and the discovery comes as
someone else's money in someone else's category.

The exact expression for the card mask is not fixed in this specification: it
is derived during implementation from real bank responses accumulated after
step 2 of the rollout, and pinned down by a table-driven test. The rule for
choosing it is conservative: when in doubt, do not recognise.

The key is derived **only for transactions with a conduit code**. A shop
purchase also sometimes has a `counterName`, but there the code decides the
category, and setting up a second rule alongside it would mean two sources of
truth for one and the same case.

### An honest limitation

From analysing the August data: `counterIban` and `counterEdrpou` apparently do
not arrive in a personal statement — they appear on sole-trader (ФОП)
accounts. A card mask was found in the description of 8 of 56 transfers. So
**most transfers will have no key**, and linking will remain an add-on that
fires when it can, not a mechanism.

The main value is sorting one by one. A missing key is the normal case, not
the exceptional one: the question is always asked, and the hint about past
transactions appears only when there is a key.

This cannot be verified in advance: the database held no full bank responses,
and the database itself was deleted after the analysis. The first weeks after
the rollout will show the real statistics of the sources.

## 6. The Telegram dialog

### The trigger does not change

The observer stays exactly as it was: "the transaction is outgoing and
uncategorized". As soon as a conduit code stops taking part in the third
layer, a transfer arrives uncategorized on its own and meets the same
condition. A second trigger is not needed.

What differs is **the question and the meaning of the answer**:

| | `kind = 'mcc'` | `kind = 'transfer'` |
|---|---|---|
| Condition | the code is not a conduit code | the code is a conduit code |
| Question | «Незнайомий MCC 5411, 340 ₴ — обери категорію» | «Переказ 2 500 ₴ · Петренко І. — куди це?» |
| Caption under the buttons | «застосується до всіх майбутніх» | «тільки цей отримувач» |
| The answer creates | an MCC binding + history recomputation | a counterparty rule, or a manual mark on the row |
| Question uniqueness | per code | per transaction |

The difference has to be visible **before** the press, otherwise a person
decides the fate of every future transfer in one motion while thinking they
are sorting one.

«Пропустити» on a transfer closes the question and leaves the transaction
uncategorized: no rule is created, and we do not ask about this transaction
again. The next transfer to the same counterparty will be asked about again —
the question is keyed by transaction.

If by the time of the answer the transaction has already been filed (for
example by a rule created from a neighbouring question), the choice does not
silently override the result: `CategoryChoiceOutcome` must have an explicit
case and message for it, as it does for a code conflict.

### A hint about the past

If a transfer has an exact key and there are unsorted transactions with the
same key, the question carries the line «раніше 3 операції цьому отримувачу»,
and the answer «перенесено 4 операції».

We build no separate linking mechanism: the layer-2 rule itself picks up every
non-manual row with the same key at the next recomputation, past ones included.
Linking is a consequence of the model, not a feature.

There will be no separate confirmation either: the rule is always saved, keys
are only exact ones, and an MCC binding also rewrites history without asking
again. An extra step would introduce a second standard of behaviour for no
reason.

### The «➕ Нова категорія» button

The last button in both layouts. Pressing it sends a **new** message with
`reply_markup: {force_reply: true}` and the text «Введи назву нової категорії»
— `force_reply` cannot be attached to an edited message, because
`editMessageText` accepts only an inline keyboard. The id of the sent message
goes into `mcc_prompts.reply_message_id`.

The answer arrives with `reply_to_message`. `TelegramUpdateHandler.handleMessage`
checks, before parsing commands: is there an open question with this
`reply_message_id`? If so, the text is the name. The whole dialog state is a
row in a table tied to a message number; if nobody answers, nothing is left
hanging.

The name: trim whitespace, require non-empty, cap the length, strip control
characters. We do not ask for an emoji — a second exchange for decoration does
not pay for itself, and it can be added on the web. The limit is 0. If a
category with that name already exists, we take it and say so, rather than
breeding a twin.

Having created the category, the bot calls **the same** choice handler as an
ordinary button. One branch decides whether to bind the code or create a rule,
and one reports the result.

### Changes to the models and the client

- `TgMessage` gains `reply_to_message: TgMessage?`.
- `TelegramClient.sendMessage` gains `forceReply: Boolean = false`.
- `encodeCallback` / `decodeCallback` gain a form for transfers, keyed by the
  question's id rather than by the code: `tx:<promptId>:cat:<categoryId>` and
  `tx:<promptId>:skip`, plus `…:new` for the create-category button. The
  `callback_data` budget is 64 bytes, and we fit.

### Locking

The question is sent from under the mutex and must stay fire-and-forget. The
answer arrives as a separate webhook and takes the mutex afresh, so there is no
recursion — but this must be pinned by a test: a violation would show up as a
hung application, not a crash.

## 7. Web

### Conduit codes

A «Транзитні коди» block on the categories page — where the «MCC через кому»
field already lives and where `MccConflictException` already rejects an attempt
to take another category's code. The same form, the same field. An attempt to
enter a conduit code into a category (and vice versa) gets an error of the same
kind.

### Counterparty rules

A list below, one row per rule: «Петренко І. → 🏠 Оренда ✕». They are created in
the bot; on the web they can only be seen and removed — a separate page for
half a dozen rows does not pay for itself. Without a way to remove one, a
single wrong answer would last forever.

### Where a transaction's category came from

On the transactions page, next to the category, a mark: «за кодом» /
«за отримувачем» / «вручну». While there was one layer the question did not
arise; with three it is mandatory, otherwise removing a code from a category
would move nothing and there would be no way to tell why.

It is computed **at render time**. The pure categorization function returns not
only the category but also the layer that decided it — one computation serves
both the filing and the mark, and they cannot diverge. A stored field would
have to be updated everywhere the rules change, and it would lie exactly when
it was needed most.

### The transactions dropdown

Today choosing a category on the web binds the code. For a conduit code it must
do what the bot does: a counterparty rule or a mark on one row, and under no
circumstances a binding. `CategoryChoiceOutcome` gains new cases and is handled
as explicitly as the current ones.

The web and the bot go through one `IngestService` method. Two entry points
doing "the same thing" differently are a source of bugs that are only visible
in production; commit `cb8eaf1` existed for exactly that reason.

### Copy

Every new string goes through `Copy`, implemented by `UkCopy` and `EnCopy`.
Category names and counterparty names are user data and do not go through
`Copy`.

## 8. Testing

The order reflects where a bug has already reached production once.

- **Layer precedence** — a table-driven test on the pure categorization
  function: manual beats rule, rule beats code, a conduit code assigns nothing.
- **The conduit-code invariant** — marking removes the binding; a conduit code
  cannot be entered into a category, neither from the web nor from the bot.
- **A rule does not make a row manual** — after the rule changes, the row has
  moved.
- **Question uniqueness** — two transfers with one code give two open
  questions; two purchases with one unfamiliar code give one.
- **`force_reply` is actually serialized** — through `MockEngine` with a check
  of the request body, not through a fake. A fake accepts any object; that is
  exactly how `reply_markup: null` made it to production, where Telegram stayed
  silent for a whole evening.
- **An answer is not confused with a command** — a message with
  `reply_to_message` to an open question becomes the category name; anything
  else goes down the ordinary branch.
- **Creating a category with an existing name** — the existing one is taken,
  no duplicate is created.
- **Key derivation** — table-driven, on samples of the bank's response shape,
  including the "field missing" and "shape not recognised" → no key cases.
- **No deadlock** — the question leaves from under the mutex, the answer takes
  the mutex afresh; the test must not hang.
- **Migration V3** — on a clean database and on one brought up to V2; the new
  tables and indexes are in place, `user_version` = 3.

## 9. Rollout order

The first three steps change nothing for the user.

1. Migration V3 and the tables.
2. Storing the bank's response whole — from this point on, what linking cannot
   work without starts accumulating.
3. Three layers in the categorizer. There are no conduit codes yet, so the
   behaviour is unchanged.
4. Managing conduit codes and rules on the web; the category-source mark.
5. The bot: the transfer question, the «➕ Нова категорія» button, handling the
   answer.
6. The user marks 4829 as a conduit code themselves.

The sixth step is left to the user on purpose. A migration would do it at
deploy time, without warning.

### What will happen at that moment

August's transfers will lose «Різне» and end up in «Без категорії»: they are
not manual, they have no key (they arrived before step 2), and the code no
longer assigns. The total is the same; the label is different.

This is correct and is not to be papered over with a freeze. While the sum sits
in «Різне» it looks sorted; in «Без категорії» it looks like what it is —
unfinished work that is visible and can be sorted from the dropdown. The bot
will ask about new transfers by itself.

## 10. Out of scope

- **Backfilling keys across history.** There are no full bank responses for
  August, and the 31-day window limits the statement for past months. August is
  sorted by hand.
- **A «тільки перекази» filter** on the transactions page. The bot asks about
  each transfer as it arrives, so the backlog clears itself; a screen for a
  task that gets solved without it.
- **An emoji when creating a category from the bot.**
- **Rules by description or by amount.** Only exact keys; the 2026-08-18
  specification rejected text heuristics, and the reason here is the same.
- **Multiple categories per transaction.**

Known problems this design does not fix, which are worth keeping on a separate
list: four unlabelled fields in the category form (the user's limits once
landed in the emoji field), and three transactions with an `account_id` that is
not in `accounts`.

## 11. Assumptions production will test

- `counterIban` and `counterEdrpou` do not arrive for personal cards. If they
  do, the share of linked transfers will grow by itself, and nothing needs to
  change.
- The card mask in the description has a recognisable shape. If recognition
  proves unreliable, the right response is to narrow the rule to a refusal, not
  to widen it.
- The user answers the bot's questions as they arrive. If there turn out to be
  too many transfers at once, question grouping will be needed — but that is a
  separate task with its own data.
