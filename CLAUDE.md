# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Maintaining this file

**Every agent that works in this repo must keep CLAUDE.md current and short.** After a change
that invalidates something here — a new command, a moved boundary, a retired invariant —
update this file in the same commit. Keep it lean: prune anything the code, the README or
`git log` already says. This is a map of decisions that cost multiple files to rediscover,
not a file listing. If a section stops paying for its length, delete it.

## What this is

Self-hosted single-user Monobank spending tracker. Transactions arrive by webhook (hourly
statement sync is the safety net), are bucketed into categories by MCC, and Telegram warns
when a category nears or passes its monthly limit. Kotlin + Ktor + Exposed + SQLite, one
Fly.io machine, one volume. Deployment, first-run setup and env vars are in [README.md](README.md).

## Commands

```bash
./gradlew test                                  # whole suite
./gradlew test --tests 'app.web.AuthTest'       # one class
./gradlew test --tests 'app.web.AuthTest.logout revokes*'   # one test (names have spaces)
./gradlew build                                 # compile + test
./gradlew fatJar                                # build/libs/app.jar, what the Dockerfile runs

ADMIN_PASSWORD=dev \
ENCRYPTION_KEY="$(openssl rand -base64 32)" \
DB_PATH=./dev.db \
DEV_INSECURE_COOKIES=1 ./gradlew run            # http://localhost:8080
```

`DEV_INSECURE_COOKIES=1` is mandatory locally — the session and CSRF cookies are `Secure` by
default and will not survive plain HTTP. Test report: `build/reports/tests/test/index.html`.

## Architecture

Wiring lives in one place: [Main.kt](src/main/kotlin/app/Main.kt) constructs every repository
and service by hand (no DI container) and mounts the routes. Read it first.

```
app.mono     HttpMonoClient        Monobank API behind the MonoClient interface
app.notify   HttpTelegramClient    Telegram API behind the TelegramClient interface
             Notifier              threshold alerts, claim-then-send
             TelegramUpdateHandler pairing, command routing, callback dispatch
             MccPromptService      "which category is this?" prompt + button handling
             LimitPromptService    /limit's category buttons + amount reply dialog
             Reports               /status and /left rendering, pure over a MonthSummary
app.ingest   IngestService         THE ONLY WRITER (see below)
             WebhookProcessor      store-then-parse drain of webhook_events
             SyncService           month statement pull, rate-limit aware
app.budget   repositories + BudgetService, Money, MonthKey, Categorizer
app.db       Exposed table objects, SQL migrations, AES-GCM Crypto, SettingsRepository
app.web      Ktor routes + kotlinx.html pages, auth, CSRF, security headers
app.i18n     Copy interface + UkCopy/EnCopy
```

### The single-writer rule

`IngestService` holds a `Mutex` and is the only path that mutates transactions,
**categories** or **accounts**. The sequence "store → recompute spending → check
thresholds" must be atomic: a category edit interleaving with an in-flight ingest can burn
a threshold for the whole month against a stale mapping. So web routes call
`ingest.updateCategory(...)`, never `categories.update(...)`.

`accounts` joined that list when `ingestLocked` started reading it to decide whether a
transaction is stored at all and whether it raises a question — a Settings save or the
hourly refresh landing mid-batch would judge half a statement page against the old account
list and half against the new. Both writers go through `ingest.setAccountsActive(...)` and
`ingest.replaceAccounts(...)`; nothing calls `AccountRepository.setActive`/`replaceAll`
directly.

Two consequences worth memorising:
- The mutex is **not reentrant**. A locked method calling another locked method deadlocks —
  hence `bindMccLocked` next to `bindMcc`. Factor out a `…Locked` private instead.
- `UnknownMccObserver.onUnknownMcc` is dispatched **after** the ingest mutex is released, not
  while it is held. It used to run inside the lock, back when it fired once per unknown MCC —
  a handful of calls per batch. A conduit code (transfers) broke that assumption: every
  transfer in a statement page is its own question, and a page of forty would have held the
  application's only writer for up to ten minutes of sequential Telegram `sendMessage`
  round-trips, blocking every web edit and webhook drain meanwhile. It must still be
  fire-and-forget: the answer to the question it raises arrives as a fresh webhook that takes
  the lock itself to apply the choice, so an implementation that blocks waiting for it has
  nothing that will ever wake it up.

### Webhooks: answer first, parse later

Monobank allows 5 seconds and disables the webhook after three failures.
[WebhookRoutes.kt](src/main/kotlin/app/web/WebhookRoutes.kt) writes the raw body to
`webhook_events`, responds 200, then triggers a drain. `WebhookProcessor.drain()` marks
*unparseable* payloads processed (they will never succeed) but leaves *transient* ingest or
DB failures unprocessed for the next retry. Bodies are capped at 64 KB — the volume is 1 GB
and holds the database too.

### The paired chat is one chat id, and it may be a group

`TELEGRAM_CHAT_ID` is the single address the bot talks to; nothing broadcasts. A group id
works everywhere a private one does, so sharing the bot with the family is a re-pairing,
not a second recipient — and every group member can press the category buttons, because
callbacks are authorised per chat, never per user.

What that costs elsewhere in [TelegramUpdateHandler.kt](src/main/kotlin/app/notify/TelegramUpdateHandler.kt):
- **A live pairing code is the authority to move the bot**, so `/start <code>` is honoured
  from *any* chat, not only the paired one — but only while the code is live and only when
  the message actually carries one. Expired, absent, or bare, a stranger's `/start` gets
  silence, never a reply and never the chance to clear the stored code. A bare `/start` is
  also not scored as a wrong guess: Telegram's START button sends one, and five taps used
  to burn the code the owner was mid-way through using. The chat being left is told
  (`chatMovedAway`) — a move must never look like a bot that just went quiet. `SettingsPage`
  shows a live code even while paired; hiding it made the move impossible from the UI.
- **Commands in a group carry `@botname`** (`/start@my_bot CODE`), and `commandForUs`
  compares that name against a cached `getMe()` before cutting it. Cutting it unread is not
  enough: privacy mode still delivers *every* slash-command in the group, so
  `/status@some_other_bot` posted the month's spending and `/start@some_other_bot CODE`
  spent the pairing code. A command naming another bot is dropped; if `getMe` has never
  succeeded, the command is answered anyway rather than leaving the bot mute.
- **Unrecognised text gets the help reply only in a private chat** (`TgChat.isPrivate`, asked
  that way round so "channel" and any type Telegram adds later land in the quiet branch).
  Telegram delivers every slash-command in a group to the bot even under privacy mode, so
  the old blanket `else` answered other bots' commands forever.
- **A move drags every unanswered question with it.** `ChatMoveObserver.onChatMoved` fires
  on a re-pairing (never on a first pairing or a supergroup upgrade) and `MccPromptService`
  retires the old message — editing it also strips the dead buttons — then asks again in the
  new chat. The invariant: **an open prompt's `message_id` always refers to the currently
  paired chat**, which is why no prompt table stores a chat id. A question that cannot be
  re-asked is closed, never left open: `create` opens no second question for a code that
  already has one, so an open question nobody can see is a code never asked about again.
- **A group upgraded to a supergroup changes its chat id.** The service message carrying
  `migrate_to_chat_id` re-points the setting; ignoring it means a bot that is silent for
  good with no symptom but missing alerts.

### The command surface, and asking one person a question

`/status [YYYY-MM]`, `/left`, `/limit`, `/help`, matched on the **exact** command name —
`startsWith` also matched `/statuses` and answered it with the month's spending. A bad
month argument is refused rather than passed through `safeMonthKey`, whose fallback would
answer a typo with a full report for a month nobody asked about. `/status` and `/left`
render in [Reports.kt](src/main/kotlin/app/notify/Reports.kt) as pure functions of a
`MonthSummary`; `/left` is a fourth reader of spending and applies the `countsAsSpending`
filter itself, like the three before it.

`/limit` is a two-step dialog — category buttons, then an amount typed as a **reply** —
and everything awkward about it comes from privacy mode: the bot receives only commands
and replies to its own messages, so an amount typed straight into the room is lost.

- **A question meant for one person must open that person's reply box**, or it is answered
  into a void. [addressQuestion](src/main/kotlin/app/notify/Addressing.kt) is the one place
  that decides how. In a group it takes **both** `selective` *and* an `@username` mention:
  Telegram aims a selective force-reply at the users named in the text, so `selective` with
  no mention targets nobody — strictly worse than not setting it. With no username to
  mention, send no force-reply at all rather than springing every member's keyboard open.
  `TgCallbackQuery.from` exists for that targeting and for saying who changed a limit —
  never for authorisation, which stays per chat.
- **A rejected amount re-asks with a fresh force-reply** and moves the dialog's
  `reply_message_id` to the new question. A plain complaint would leave the next attempt
  typed into a room the bot cannot hear, and two live reply targets would let one question
  be answered twice.
- **An open `/limit` dialog is closed when the bot moves**, not re-homed like an MCC
  question: a half-typed limit dragged into a chat that never started it asks someone to
  finish a stranger's sentence. Same invariant either way — an open prompt's `message_id`
  always refers to the currently paired chat — which is why neither prompt table stores a
  chat id or an expiry. Cancel closes the question too: the keyboard outlives the pick.
- **`limit_prompts.category_id` carries no foreign key on purpose.** Cascading the delete
  would take the open question away with the category and leave whoever had just been asked
  for an amount replying into silence.

### Categorization

Three layers, most specific first ([Categorizer.kt](src/main/kotlin/app/budget/Categorizer.kt)):
a row marked `manually_categorized`, then a rule on `counterparty_key`, then the MCC
binding. `decideCategory` returns the deciding layer as well as the category, and the
transactions page renders it — with three layers, "why is it here?" has no other answer.

Codes listed in `conduit_mcc` (4829 and its kind) take no part in the MCC layer: the code
says money left by transfer and nothing about why. **A conduit code may never be bound to
a category** — a binding would keep filing transfers and the prompt that marking it conduit
exists to provoke would never fire. `IngestService.setConduitMccs` drops any binding it
finds; `CategoryRepository` throws `ConduitMccException` on the way in.

A counterparty rule is created when a transfer is filed and its recipient could be
identified from the payload — see [CounterpartyKey.kt](src/main/kotlin/app/budget/CounterpartyKey.kt),
whose ladder is IBAN, EDRPOU, masked card, then name, and whose answer is null whenever the
shape is not recognised. **A row filed by a rule is not marked manual**, so a later change
to the rule still reaches it. A transfer with no key is a manual override of that one row.

Choosing a category **binds the MCC and rewrites matching history** for ordinary codes,
from both the Telegram prompt and the transactions dropdown; both go through
`IngestService.setTransactionCategoryChoice`, which returns a `CategoryChoiceOutcome`
sealed class so no case is silently dropped. Clearing a category never unbinds the MCC.
Only outgoing amounts (`amountMinor < 0`) count as spending and only they trigger a prompt.

### Spending truth: three ways a transaction stops counting

`TransactionRepository.spentByCategory` is the one place spending is *computed*, but
`BudgetService.monthSummary`'s `totalSpentMinor` is not the one place it is *read* — the
account exclusion is enforced upstream, in `spentByCategory` itself, but the category
exclusion is a per-reader filter on `summary.categories`, and every reader that builds its
own total or its own alert must apply it. `monthBreakdown` (Огляд's stacked bar and
breakdown list, `app/web/Breakdown.kt`), `Notifier`, `/status`'s `renderStatus` and
`/transactions`' own day-group header (`countedDayTotal`) each do; a fifth reader that
forgets is a header and a body that disagree, not a compile error — which is exactly how
the day headers came to sum 84% above the month total on the same page, caught by a
walkthrough of the live app. They compose: a transaction is excluded if
*any* of them is true.

**Excluding is half the job: the row has to say so.** Wherever excluded rows are rendered
beside a total that drops them, they carry a pill — `inactiveAccountPill` for the account,
`notCountedPill` for the category, both on `/transactions` and the second echoed by
`/status`. A silent exclusion is a header sitting above rows that visibly sum to something
else, which reads as a broken sum rather than as a rule.

- **An inactive account.** `spentByCategory` joins `accounts` and drops any row that is not
  `active AND currency = UAH` (an unknown/unsynced account counts as tracked — see the
  method doc). Blanket: nothing shows this money anywhere in the totals. The rows
  themselves still render on `/transactions`, where they carry the
  `рахунок вимкнено — не рахується` pill and are left out of the day total — both driven by
  `AccountRepository.untrackedIds()`, which is that same predicate inverted. Use it, not
  `filterNot { it.active }`: the looser test left a non-hryvnia account that was still
  switched *on* unmarked and counted. An account absent from Monobank's
  response keeps whatever `active` it already had — `AccountRepository.replaceAll` inserts
  or updates rows for accounts it hears about and never deletes one that goes quiet, so a
  switched-off account cannot be resurrected just by leaving the token's scope.
  The exclusion is enforced in a **second** place, on the ask side: `IngestService`
  raises no unknown-MCC/transfer question for a row on an untracked account
  (`AccountRepository.untrackedIds`, same unknown-counts-as-tracked rule). `SyncService`'s
  `activeIds` is not enough — Monobank registers the webhook per *client*, so it pushes
  every account the token can see whatever Settings says, and asking about money that
  counts towards nothing is pure noise.
- **A non-hryvnia account**, which is the same exclusion with teeth. `amount_minor` is
  hryvnia kopecks end to end and `formatMinor` appends ₴ unconditionally, so a dollar row
  is not unsupported-but-harmless — it is a wrong number on every screen that reads it.
  `StoredAccount.tracked` is `active && isUah`, and three gates keep it out:
  `IngestService` **refuses to store** a transaction on a known non-hryvnia account (the
  only exclusion that discards bank data — owner's ruling, 2026-09-22), `spentByCategory`
  drops any that predate that gate, and `setActive` won't switch one on. `replaceAll`
  stores every account Monobank reports, not just hryvnia ones as it once did: an account
  with **no row** counts as tracked, so filtering them out at the door was what let the
  dollar account be counted and asked about in the first place. Settings lists only
  `trackableList()`, and `/transactions` drops these rows outright (`page`/`count` take
  `excludeAccountIds`) — a switched-off *hryvnia* row stays listed under a pill because its
  amount is at least true, while `formatMinor` would print a dollar one as "44 600 ₴".
  Never gate on `transactions.currency_code` — that is Monobank's *operation* currency,
  840 on a hryvnia card used abroad.

Because every gate reads rows in `accounts`, an empty or stale table fails **open**.
`Main.kt` refreshes accounts once at boot, before the hourly loop's first `delay`, and
`syncMonth` refreshes on every run: under the old "only when the table is empty" rule the
whole currency gate was inert for the first hour after any deploy. Counts read side by side
must share one predicate — `countUnresolved` carries `spentByCategory`'s, while plain
`count` stays account-blind because it backs "Усього: N", which must match the rendered
rows.
- **`Category.countsAsSpending = false`.** For self-transfers ("Свої рахунки"-style
  categories) where the account on *both* ends is active, so the join above can't help.
  Unlike the account exclusion, this one stays visible: `spentByCategory` still reports
  the category's real amount, and every reader drops it from its own total individually —
  `BudgetService.monthSummary` for `totalSpentMinor` (the category's own row, limit bar and
  `/transactions` are unaffected), `monthBreakdown` and `Notifier` for the breakdown list
  and threshold alerts, and `renderStatus` for the `/status` header, which also suppresses the ⚠️/🔔 mark
  and appends `notCountedPill` to that category's line so the header and the line under it
  never silently disagree.

### Migrations

Numbered SQL files in `src/main/resources/db/migrations/` (`Vn__name.sql`), registered in
`Migrations.kt`'s `MIGRATIONS` list, applied by `runMigrations`. It splits each file on
`;` to get one `exec()` call per statement — **a `;` anywhere in the file ends a
statement, including inside a `--` comment**, not just inside a trigger body or a string
literal. A semicolon in a comment silently truncates it there; the rest of the comment
text becomes the start of the next "statement" and breaks with an opaque JDBC error
("prepared statement has been finalized"), not a syntax error pointing at the real cause.
Write migration comments with no `;` in them at all rather than trying to keep the
one real terminator apart from the accidental ones.

### Time and money

Months are calendar months in `Europe/Kyiv`, fixed in code, never read from `TZ`
([MonthKey.kt](src/main/kotlin/app/budget/MonthKey.kt)). Month keys from query strings must
go through `safeMonthKey`. Amounts are `Long` minor units end to end; render with
`formatMinor`, parse with `parseAmountToMinor`.

### Secrets and settings

Everything configurable at runtime — both API tokens, chat pairing, language, webhook
secrets, session epoch — lives in the `settings` table, values encrypted with AES-GCM under
`ENCRYPTION_KEY`. Only `ADMIN_PASSWORD` and `ENCRYPTION_KEY` come from the environment.
Never log an exception from the Telegram path without `withRedactedTelegramToken()` — the
bot token is in the message. Monobank's webhook secret is in the URL path; `redactWebhookPath`
keeps it out of access logs.

### Web security invariants

Breaking any of these has already broken the app in production once:
- **CSP is `default-src 'none'` with `script-src 'self'`** — no inline `<script>`, no
  `onclick`, no `style="…"` attributes. Behaviour goes in `static/app.js` and attaches via
  `data-` attributes.
- **Every form needs `csrfField(token)`**, and every GET handler rendering a form must call
  `call.csrfToken(secureCookies)` first. Missing token = 403. Handlers read the body via
  `call.formParameters()`, not `receiveParameters()` — the CSRF gate already consumed it.
- **`Referrer-Policy` must stay `same-origin`**, not `no-referrer`: Chrome derives `Origin`
  from it and posts `Origin: null` under `no-referrer`, which locked every form out.
- Logout revokes globally by bumping the persisted session epoch; the cookie is signed, and
  carries `issuedAt` / `epoch` / password `fingerprint`.
- `/webhook/` and `/tg/` are the only CSRF-exempt routes (machines cannot send `Origin`).

### User-facing text

Every string a person reads goes through the [Copy](src/main/kotlin/app/i18n/Copy.kt)
interface, implemented by `UkCopy` and `EnCopy` — an interface, not a map, so a missing
translation is a compile error. Get it with `settings.copy()`. Category names and emoji are
user data and never go through `Copy`; money and `dd.MM` formatting stay as `app.budget`
renders them. Default language is Ukrainian.

`Category.label` is `"$emoji $name"` and is the one string Telegram buttons, the Огляд
breakdown list, the Категорії list and the dropdowns all render, so junk in `emoji` shows
up everywhere at once. The category editor runs the field through `sanitizeEmoji` (digits
and spaces dropped) because an unlabelled box next to the limit box got typed into once
already; `V2__strip_limits_from_emoji.sql` is that same rule applied to the rows it
happened to.

### Build pins

`build.gradle.kts` pins Netty **ahead of** what Ktor 3.0.3 selects, because that version has
open HTTP request smuggling advisories and this deployment shape (Fly proxy in front of a
Netty origin) is exactly the vulnerable one. Re-check OSV before bumping Ktor or relaxing
the constraint. Every HTTP client must be built with `API_JSON` from
[ApiJson.kt](src/main/kotlin/app/ApiJson.kt) — tests once configured their own and drifted,
so every plain-text Telegram message failed in production while the suite stayed green.

## Testing conventions

Tests never touch the real Monobank or Telegram: `FakeMonoClient`, `FakeTelegramClient` and
Ktor's `MockEngine` cover them. `withTestDb { db -> … }` gives a migrated temp SQLite
database. Route tests use `testApplication` and mount only the routes under test; test names
are backticked sentences. Clocks are injected (`clock: Clock = Clock.system(KYIV)`) — fix
them in tests rather than sleeping.

## Ops constraints that shape code

`max_machines_running = 1` in `fly.toml` — SQLite on a Fly volume tolerates exactly one
writer, never raise it. The machine never sleeps (a cold JVM misses Monobank's 5s timeout).
Background work uses the application's own `CoroutineScope` (`launch { }` inside `module`),
never `GlobalScope`, so it dies with the app; the hourly loop drains events, syncs the
current month and prunes processed webhook rows older than 30 days.

Commit messages: lowercase `feat:` / `fix:` prefix, subject states the behaviour or the bug
in plain words (`fix: no-referrer made Chrome post Origin: null, blocking every form`).
