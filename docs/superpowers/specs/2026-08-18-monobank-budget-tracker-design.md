# Monobank Budget Tracker — design

Date: 2026-08-18
Status: approved, ready for the implementation plan

## 1. Goal and scope

A self-hosted web service for tracking spending by category on Monobank. One
user. The service receives transactions from Monobank, sorts them into
categories by MCC, computes spending for the current month and notifies in
Telegram when a category approaches its limit and when it passes it.

The priorities are simplicity, reliability and minimal running cost. This is
not a financial aggregator.

### Usage scenario

1. Clone the repository, deploy to Fly.io.
2. Open the web interface, log in with `ADMIN_PASSWORD`.
3. Enter the Monobank token and the Telegram bot token in Settings.
4. Pair the Telegram chat: send the bot `/start <code>`.
5. Register the Monobank webhook and run the initial sync.
6. Create categories, give them MCCs and limits.
7. From then on the service runs by itself: transactions arrive by webhook,
   unknown MCCs are resolved with buttons in Telegram, and notifications arrive
   when thresholds are reached.

### Out of scope for the MVP

Registration and multiple users; non-UAH accounts and currency conversion;
categorization by transaction description; budgets over periods other than
the calendar month; export; a mobile app; e2e tests.

## 2. Technology stack

| Layer | Choice |
|---|---|
| Language / runtime | Kotlin, JVM 21 |
| HTTP server | Ktor |
| Concurrency | Kotlin Coroutines |
| Database | SQLite (WAL), Exposed |
| UI | Server-rendered `kotlinx.html` + targeted inline JS |
| Deployment | Docker, Fly.io, persistent volume |
| Notifications | Telegram Bot API |

No microservices. No Redis. No PostgreSQL. No separate frontend server. No DI
framework — the dependency graph is assembled by hand in `Application.module()`.

## 3. Architecture

A layered monolith, packages by domain:

```
mono      Monobank HTTP client + API models
budget    categories, categorization, spending calculation
notify    Telegram: sending, receiving updates, threshold deduplication
web       Ktor routes + kotlinx.html
db        Exposed tables, migrations, crypto for secrets
```

### A single write path

**Every state change goes through `IngestService`, whose calls are serialized
by a `Mutex`.** There are four write sources: the Monobank webhook, the
background and manual sync, a category edit on the web, and a button press in
Telegram. SQLite allows one writer, and the sequence "store the transaction →
recompute spending → check thresholds" has to be atomic: otherwise a webhook
delivered twice in parallel produces two notifications.

### The webhook path

`POST /webhook/{secret}` first writes the raw payload to `webhook_events` and
responds `200`. Parsing happens after the response. The reason: Monobank
waits 5 seconds for a response and disables the webhook for good after three
consecutive failures. With the raw record on disk, a machine restart between
receiving and parsing loses no data.

### Reliability

`min_machines_running = 1` — a cold JVM start does not fit inside Monobank's
5-second timeout. Plus an hourly background sync: if the webhook does drop
(a deploy, an OOM, a Fly incident), the data converges on its own.

## 4. External APIs: confirmed constraints

**Monobank Open API** (checked against the documentation):

- `/personal/statement` and `/personal/client-info` — no more than **1 request
  per 60 seconds**. The manual Sync button is disabled for that long.
- A statement covers at most **31 days + 1 hour**, paginated at 500 records.
- Webhook registration: Monobank sends a **`GET`** to the given URL and requires
  exactly `200`. Then `POST` to the same URL.
- Response timeout is **5 seconds**, retries after 60 and 600 seconds, and
  **after the third failure the webhook is disabled**.
- The personal API has no request signatures. The standard protection is a
  random secret in the URL path (the official example:
  `https://example.com/some_random_data_for_security`).
- `StatementItem.hold = true` means a held amount; after settlement the
  `amount` of the same transaction may change.
- `mcc` is the normalized code, `originalMcc` the original.

**Telegram Bot API:**

- `setWebhook` accepts a `secret_token`; Telegram sends it back in the
  `X-Telegram-Bot-Api-Secret-Token` header.
- `callback_data` is at most 64 bytes.
- `answerCallbackQuery` is mandatory, otherwise the user is left with a spinner.

## 5. Database schema

```sql
accounts            id TEXT PK, masked_pan, type, currency_code, balance_minor,
                    active, updated_at
categories          id INTEGER PK, name, emoji, monthly_limit_minor, threshold_pct,
                    notify_warning, notify_exceeded, enabled, position, created_at
category_mcc        mcc INTEGER PK, category_id INTEGER FK -> categories ON DELETE CASCADE
transactions        id TEXT PK, account_id, occurred_at INTEGER, month TEXT,
                    amount_minor INTEGER, currency_code, description, mcc,
                    original_mcc, hold INTEGER, category_id FK NULL ON DELETE SET NULL,
                    manually_categorized INTEGER, raw_json TEXT, created_at
webhook_events      id INTEGER PK, payload TEXT, received_at, processed_at NULL
notification_events id INTEGER PK, category_id, month TEXT, threshold INTEGER,
                    notified_at, UNIQUE(category_id, month, threshold)
mcc_prompts         id INTEGER PK, mcc INTEGER, transaction_id TEXT, message_id INTEGER,
                    created_at, resolved_at NULL,
                    UNIQUE(mcc) WHERE resolved_at IS NULL
settings            key TEXT PK, value TEXT, encrypted INTEGER
```

Indexes: `transactions(month, category_id)`, `transactions(occurred_at)`,
`webhook_events(processed_at)`.

### Rationale for the non-obvious decisions

**`transactions.id` is Monobank's identifier, not an autoincrement.**
Idempotency becomes a property of the schema: a repeated delivery runs into the
primary key. The `id` and `transactionId` fields from the brief are collapsed
into one.

**Writes are an upsert, not `INSERT OR IGNORE`.** A transaction first arrives
with `hold = true`, and after settlement the amount may change. The rule: if
the stored row has `hold = true`, `amount_minor`, `hold` and `description` are
updated; if `hold = false`, the row is final and is not overwritten.

**A `month TEXT` column in the form `'2026-08'`, computed in Europe/Kyiv on
write.** SQLite cannot convert time zones properly, and grouping by month is
needed in every dashboard query. Denormalizing removes a class of bugs at month
boundaries and allows a composite index.

**`amount_minor INTEGER` with Monobank's sign as is.** Spending is negative;
refunds and incoming transfers are positive. Float/Double are not used
anywhere. See §6 for how the sign is treated in the spending calculation —
that decision was revised on 2026-08-24, and "a refund reduces spending" no
longer holds without qualification.

**`category_mcc` with the primary key on `mcc`.** The database physically
refuses one MCC in two categories. A transaction with no match stays at
`category_id = NULL` and is shown as `Uncategorized`.

**`mcc_prompts.transaction_id`** is the transaction that triggered the
question; it is needed only for the message text ("McDonalds, 320 ₴"). The
answer is applied not to that one transaction but to every transaction with
this MCC — through the shared history recomputation.

**`settings`** is the only key-value table, and its contents are fixed:

| Key | Encrypted | Purpose |
|---|---|---|
| `mono_token` | yes | Monobank token |
| `telegram_token` | yes | Telegram bot token |
| `telegram_chat_id` | no | the paired chat; empty until `/start` |
| `mono_webhook_secret` | no | random path segment of the Monobank webhook |
| `telegram_webhook_secret` | no | the `secret_token` value for Telegram |
| `pairing_code` | no | one-time chat pairing code; deleted after use |
| `default_threshold_pct` | no | default threshold for new categories (80) |
| `last_sync_at` | no | to disable the Sync button for 60 seconds |

## 6. Categorization and spending calculation

### Categorization

```kotlin
fun categorize(mcc: Int?, mapping: Map<Int, Long>): Long?   // -> categoryId | null
```

A pure function, one lookup in `category_mcc`. There are no heuristics on the
transaction description: there is one rule and it is predictable, and manual
override covers the remaining cases. The `mcc` field is used; `original_mcc` is
stored only for investigating errors.

`manually_categorized = true` grants immunity: no automatic recomputation
touches such a transaction.

### Recomputation when the mapping changes

When an MCC is added to or removed from a category, **every transaction across
the whole history** is recategorized, except those marked
`manually_categorized`. Thresholds are re-evaluated **for the current month
only** — past months do not produce retroactive notifications.

### Spending calculation

Nothing is materialized:

```sql
SELECT category_id, -SUM(amount_minor) AS spent
FROM transactions WHERE month = ? AND amount_minor < 0 GROUP BY category_id
```

A couple of thousand rows a month through the `(month, category_id)` index —
microseconds. A spending cache would be a source of drift with no gain.

**Revised 2026-08-24: only rows with a negative `amount_minor` count as
spending; positive amounts are ignored entirely rather than subtracted.** The
original specification summed every sign and flipped the result, on the theory
that a refund cancels the purchase it reverses. On real data that did not
survive the first month: Monobank marks incoming transfers with MCC 4829 — the
same code and the same positive sign as a refund, with nothing to tell them
apart. In the owner's August, the transfers category came out at
−120 000,00 ₴ and dragged the month's headline total down to 7 000,00 ₴, while
restaurants alone had cost 34 000 ₴: the number was not merely incomplete but
confidently wrong. The owner's ruling: under-counting a genuine refund is an
acceptable price, showing income as negative spending is not. `hold = true`
still counts as spending (see below); the transaction list still shows every
row, including positive ones — only the spending arithmetic narrows.

### Rules that do not follow directly from the brief

- **Transactions with `hold = true` count as spending.** A held amount is money
  already spent; waiting for settlement would understate spending and delay the
  limit notification. The amount is corrected on upsert.
- **A negative `spent` is no longer possible** — since only negative
  `amount_minor` values are summed, the result is always `>= 0`. The progress
  bar's clamp at `0%` (`.coerceAtLeast(0)` in `BudgetService`) remains as
  defensive code, but normal operation can no longer reach it.
- **`enabled = false`** takes the category out of categorization and out of
  notifications, but transactions already assigned keep their `category_id`.
  History is not rewritten.

### Statuses

`< threshold_pct` — normal, `>= threshold_pct` — warning, `>= 100%` — exceeded.
`threshold_pct` is set per category; the default value (80) lives in Settings.

## 7. Thresholds and notifications

```kotlin
fun thresholdsCrossed(spentMinor: Long, limitMinor: Long, warningPct: Int): List<Int>
// -> [] | [warningPct] | [100] | [warningPct, 100]
```

**Deduplication is a unique index, not a check in code.** The service tries to
insert a row into `notification_events`; the fact of a successful insert is
the right to send. If the insert fails, the notification has already gone
out. A spending sequence of 14 000 → 14 100 → 14 200 gives one insert and two
refusals. A separate "have we already sent this?" `SELECT` is not acceptable:
a second webhook delivery slips in between it and the `INSERT`.

**The reset for a new month is free** — `month` is part of the key. No cron
is needed.

**The order is: claim → send → on error, delete the claim.** If Telegram is
unavailable, the row is deleted and the next transaction in that category will
try again. The reverse order produces two messages on a double delivery. A
known gap: the process dying between the insert and the send loses one
notification. For a single user that is cheaper than a full outbox.

**If one payment jumps both thresholds** (0% → 120%), both claims are written,
but only the 100% message is sent.

**A Telegram error does not break ingest** — sending is wrapped in a
`try/catch` with a log line. The webhook must return `200` within 5 seconds.

**Until a chat is paired, thresholds are not claimed** — the row is written
only on an actual send. Otherwise the first month before the bot was set up
would silently use up every threshold.

Notifications are not sent for categories with `enabled = false`, categories
without a limit, or `Uncategorized`.

The message format when a limit is exceeded:

```
⚠️ Budget exceeded

Category: 🏠 Дім
Limit: 10 000 ₴
Spent: 10 240 ₴
Over: 240 ₴
```

## 8. HTTP layer and UI

Forms send `POST` and the server answers with a redirect (Post/Redirect/Get).
There is no JSON API. There is no frontend build: one static CSS file from the
resources and a few lines of inline JS (delete confirmation, filter
auto-submit).

```
GET  /                             dashboard, ?month=YYYY-MM (defaults to current)
GET  /categories                   list + CRUD forms
POST /categories                   create
POST /categories/{id}              update (limit, MCC, threshold, flags, enabled)
POST /categories/{id}/delete       delete
GET  /transactions                 ?month=&category=, paginated
POST /transactions/{id}/category   manual override
GET  /settings                     tokens (masked), statuses, actions
POST /settings/tokens              save the Monobank / Telegram tokens
POST /settings/sync                manual sync
POST /settings/webhook             register the Monobank webhook
POST /settings/test-notification   test message to Telegram
GET|POST /webhook/{secret}         Monobank, unauthenticated
POST /tg/updates                   Telegram, unauthenticated
GET  /health                       for Fly
GET|POST /login, POST /logout
```

**Authentication is a session cookie + a login form**, with the password
checked against `ADMIN_PASSWORD` from the environment. Basic auth is shorter,
but a browser cannot log out of it, and the cookie is needed for flash
messages anyway.

**Two routes are deliberately outside authentication** — `/webhook/{secret}`
and `/tg/updates`: neither Monobank nor Telegram can log in. Their protection
is described in §9. These are the only public routes, and that has to be kept
in mind on every routing change.

**`GET /webhook/{secret}` returns an empty `200`** — Monobank verifies the URL
with exactly that request, and registration fails without it.

**Deleting a category does not delete transactions** — `ON DELETE SET NULL`,
so they move to `Uncategorized`; MCC bindings are deleted by cascade.

**The Sync button knows about Monobank's rate limit**: if fewer than 60 seconds
have passed since the last call, it is disabled and shows when it will come
back.

The dashboard is category cards with a progress bar and a colour by status,
with `Total spent` at the top and `Uncategorized` as a separate card without a
limit. Clicking a card leads to `/transactions?category=`.

## 9. Secrets and security

**Everything is configured through the web, except two environment
variables:**

| Variable | Purpose |
|---|---|
| `ADMIN_PASSWORD` | web interface login |
| `ENCRYPTION_KEY` | AES-GCM key for tokens in the database |

`ADMIN_PASSWORD` is required before the first start rather than set on the
first visit: a fresh instance with a public URL and no password is set up by
whoever finds it first — against their own Telegram.

**The Monobank and Telegram tokens are stored in `settings` under AES-GCM**
with the key from `ENCRYPTION_KEY` (a random nonce per value). A leaked volume
snapshot is useless without the key. The UI shows the tokens masked; the full
value is never sent out.

**The Monobank webhook** is protected by a long random path segment
(`MONO_WEBHOOK_SECRET`, generated on first registration and stored in
`settings`) — the personal API has no signatures, and this is the standard
model.

**The Telegram webhook** is protected by `secret_token`: the
`X-Telegram-Bot-Api-Secret-Token` header is checked, and a mismatch returns
`401`. A header is more robust than a secret in the path: it does not leak into
proxy logs or browser history. That is why the route path is fixed
(`/tg/updates`) — a random segment on top of a checked header adds nothing and
would require a ninth key in `settings`.

**Chat pairing uses a one-time code.** Settings shows `/start A7F3K2`; the bot
accepts a `chat_id` only together with the correct code, and the code is burned
after use. A "first to write becomes the owner" scheme is unacceptable: the
bot's name can be found by searching Telegram.

Secrets do not go into git — `.gitignore` covers `.env`, `*.db`, `build/`.
HTTPS is provided by Fly.io.

## 10. Telegram bot

The bot is a second way into the system, not just an outbound channel.

**Updates are received by webhook** at `POST /tg/updates`. The server calls
`setWebhook` with a `secret_token` from a button in Settings. Long polling is
not used: it holds a connection open and complicates restarts.

**An unknown MCC gets one question per MCC, not per transaction.** The partial
unique index `UNIQUE(mcc) WHERE resolved_at IS NULL` guarantees that five
purchases at an unfamiliar shop in a day produce one message. The buttons are
the enabled categories, two or three per row, plus "Skip"; `callback_data` of
the form `mcc:5712:cat:3` fits in 64 bytes.

**A button press is the same operation as a category edit on the web**, and
goes through the same `IngestService` under the mutex: insert into
`category_mcc` → recompute the whole history → recheck the current month's
thresholds. Then `answerCallbackQuery` and `editMessageText` — the message
turns into "`5712` → 🏠 Дім" and the buttons disappear. A repeated press on the
old message runs into `resolved_at` and answers "already bound". If the MCC was
bound on the web in the meantime, the bot says so and does not create a
primary-key conflict.

**Commands:**

| Command | Action |
|---|---|
| `/start <code>` | chat pairing, the only mutating command |
| `/status` | a summary across all categories, as on the dashboard |
| `/help` | the list of commands |

## 11. Synchronization

**Background** — a scheduled coroutine, once an hour: pulls the statement for
the current month and adds any missing transactions. Insurance in case the
webhook is disabled.

**Manual** — the Sync button in Settings, respecting the 1-request-per-60-seconds
limit.

**Initial sync** — for the current month, run on the first successful setup of
the Monobank token.

All three paths go through the same upsert as the webhook, so a repeated
import is safe by construction. The "31 days + 1 hour" limit on a statement
request means a request for one month always fits into a single window;
pagination at 500 records is handled with a loop.

## 12. Deployment

**A two-stage Docker build:** Gradle builds a fat JAR, and the runtime is
`eclipse-temurin:21-jre-jammy`. Not Alpine: `sqlite-jdbc` pulls in a native
library, and on musl that fails at runtime rather than at build time. The JVM
is capped with `-XX:MaxRAMPercentage=75`.

**`fly.toml`:** `min_machines_running = 1`, `max_machines_running = 1`,
`auto_stop_machines = false`, `shared-cpu-1x` / 512 MB, a volume at `/data`,
the database at `/data/budget.db`, a health check on `GET /health`.

**One machine is a requirement, not a setting.** A Fly volume mounts to one
machine, and two SQLite writers would diverge. There is a separate warning in
the README.

**SQLite is opened in WAL mode** with `busy_timeout = 5000` and
`foreign_keys = ON`. WAL is needed so that dashboard reads are not blocked by a
webhook write.

**Migrations use a small runner of our own on `PRAGMA user_version`**, with
numbered `.sql` files in the resources. Flyway for five tables is a needless
dependency.

**The time zone is set in code** (`ZoneId.of("Europe/Kyiv")`), not by the `TZ`
variable: computing the month is a domain rule and must not depend on the
environment.

**Backups** are daily Fly volume snapshots. Without `ENCRYPTION_KEY` the tokens
in a snapshot cannot be recovered; the README has a line about that.

## 13. Testing

Development is test-driven. Three levels.

**Pure functions** — unit tests with no database and no network: `categorize`,
`thresholdsCrossed`, computing `month` in Europe/Kyiv. The last one with a
table of boundary cases: 31 August 23:59 Kyiv time, 1 September 00:01, the
switch to winter time.

**The storage layer and `IngestService`** — on real SQLite in a temporary file,
without mocks. Mandatory scenarios:

- the same transaction twice → one row and one notification;
- `hold = true` → `hold = false` updates the amount; a finalized one is not
  overwritten;
- only negative amounts count as spending; a positive amount (a refund or an
  incoming transfer) does not reduce a category's spending and does not by
  itself produce negative spending;
- recomputing history does not touch `manually_categorized`;
- three crossings of the same threshold → one send;
- the threshold reset at a month boundary.

**HTTP** — through Ktor's `testApplication`: `GET /webhook/{secret}` returns
`200`; `POST` responds before parsing; a wrong
`X-Telegram-Bot-Api-Secret-Token` → `401`; any other route without a session
redirects to login.

**Monobank and Telegram sit behind interfaces**, and the tests use the Ktor
client's `MockEngine` with fixed responses, including `429` and a timeout. The
tests never call the real Monobank: a limit of one request per minute would
make the build fail at random.

There is no E2E — the Definition of Done items are checked by hand after the
first deploy.

## 14. Definition of Done

The MVP is done when it is possible to:

1. Run the application on Fly.io via Docker.
2. Open the web interface and log in with the password.
3. Configure the Monobank token through the web.
4. Configure the Telegram bot token through the web and pair the chat by code.
5. Fetch the current accounts.
6. Import the current month's transactions.
7. Create a category and give it an MCC and a limit.
8. See a category's spending on the dashboard.
9. Change the category of a specific transaction on the web.
10. Receive a webhook for a new transaction and see it in the UI.
11. Receive a question about an unknown MCC in Telegram and bind a category
    with a button.
12. Get `/status` in Telegram.
13. Receive a notification when a threshold is reached, and not receive a
    duplicate.
14. Survive an application restart without data loss.

## 15. Open questions

None. Every decision has been made and recorded above.
