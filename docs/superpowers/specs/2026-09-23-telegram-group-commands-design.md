# Telegram commands for a shared group — design

Date: 2026-09-23
Status: approved section by section, awaiting review
Branch: `worktree-telegram-group-chat`, on top of `cc16642`

Extends [2026-08-31-transfers-and-counterparty-rules-design.md](2026-08-31-transfers-and-counterparty-rules-design.md).
Where the two disagree, this one wins.

Written in English, unlike the two earlier specs, because the repo rule is that all repo
content is English.

## 1. Goal

`cc16642` made the bot movable into a family group. What it cannot do there is be useful:
the group can read exactly one number (`/status`, current month) and can change nothing.
Every limit still goes through the web UI, which only the owner can reach.

This adds the command surface that makes a shared group worth having:

- `/left` — how much room is left in each category, smallest first. The question a family
  actually asks out loud, and the one `/status` does not answer.
- `/status <month>` — the same report for a past month.
- `/limit` — change a monthly limit from the chat, by buttons, with no web login.
- `/help`, and a registered command menu so all of it is discoverable.

Plus the two pieces of plumbing a group needs before any of the above is safe: knowing
who pressed a button, and asking one person a question without opening the reply keyboard
for the whole room.

## 2. What already landed and is not restated here

`cc16642` covers re-pairing into a group, `@botname` command addressing verified against
`getMe()`, the help-reply fallback being private-chat only, and supergroup migration.
CLAUDE.md § "The paired chat is one chat id, and it may be a group" is the reference.

That branch has an open review (session `mono-category-66`). Its confirmed findings —
the pairing-attempt counter, `cachedBotUsername` invalidation, `TgChat.isGroup` vs
`"channel"`, and MCC prompt orphaning on a chat move — are **not** in this spec's scope
and are being fixed separately. § 15 records where the two touch.

## 3. The constraint everything else follows from

The bot runs with Telegram privacy mode **on** — the owner should not have to let it read
the family's conversation. Telegram then delivers only:

1. messages starting with `/`,
2. messages that @-mention the bot,
3. replies to the bot's own messages,
4. service messages.

Two consequences drive the whole design:

**A plain typed message is invisible.** If the bot asks "what amount?" and someone types
`8500` into the group, the bot never receives it. The dialog dies with no error, no retry,
and nothing on screen to explain it. So every question the bot asks must either arrive
with the reply box already open, or say in words that the answer must be a reply — and a
rejected answer must re-ask the same way, or the second attempt vanishes too.

**`force_reply` in a group targets everyone.** `ForceReplyMarkup`
([HttpTelegramClient.kt:62](../../../src/main/kotlin/app/notify/HttpTelegramClient.kt#L62))
sends `force_reply` with no `selective`, so the reply keyboard springs open for every
member of the group. This is already live: `MccPromptService.askForName`'s
"what shall I call it?" question does it today, on this branch.

`selective: true` alone does **not** fix it. Telegram aims a selective force-reply at
users @-mentioned in the message text, or at the sender of the message the bot is
replying to. `askForName`'s message is neither, so `selective` would target nobody and the
reply box would open for no one at all — strictly worse. Targeting requires knowing who
pressed the button, which the code cannot currently do.

## 4. Knowing who pressed: `TgCallbackQuery.from`

`TgCallbackQuery` carries `id`, `data` and `message`, and no sender
([TelegramUpdates.kt:36-40](../../../src/main/kotlin/app/notify/TelegramUpdates.kt#L36-L40)).

```kotlin
@Serializable
data class TgUser(
    val id: Long,
    val username: String? = null,
    @SerialName("first_name") val firstName: String = "",
)

@Serializable
data class TgCallbackQuery(
    val id: String,
    val data: String? = null,
    val message: TgMessage? = null,
    val from: TgUser? = null,
)
```

Optional with a default, like every other field in that file, so an unexpected payload
deserialises rather than throwing inside the webhook route.

Two uses, both user-facing:

- **Targeting** the reply question (§ 5).
- **Attribution** in the confirmation: `🛒 Продукти: 8 000 ₴ → 8 500 ₴ (Оля)`. In a shared
  budget "who changed this?" is a question that otherwise gets asked out loud. Display
  name is `username` when present, else `firstName`, else — both empty — no attribution
  suffix at all rather than an empty pair of brackets.

**`from` is not used for authorisation.** Callbacks are authorised per chat, never per
user, and that is deliberate: it is what lets anyone in the shared group answer a category
question. `/limit` is open to every group member by the same rule. Introducing an "owner"
distinction is out of scope and would be a new concept, not a refinement of an existing one.

## 5. Asking one person a question

`ForceReplyMarkup` gains `selective`:

```kotlin
private data class ForceReplyMarkup(
    @SerialName("force_reply") val forceReply: Boolean = true,
    val selective: Boolean = false,
)
```

`TelegramClient.sendMessage` gains a `mention: TgUser? = null` parameter. When it is
non-null and the user has a `username`, the sender prepends `@username, ` to the message
text and sets `selective = true`. Telegram parses plain-text `@username` mentions without
any `parse_mode`, so this needs no change to how messages are encoded.

**When the presser has no `@username`**, no mention can be made in plain text — a
`text_mention` entity would need `parse_mode` and an entity array, which this client does
not have and should not grow for one case. The fallback is: **send no `force_reply` at
all**, and let the question's own text ask for a reply. Falling back to a non-selective
`force_reply` would yank the keyboard open for the entire group, which is the bug being
fixed.

So the question text must carry the "reply to this message" instruction in **both** cases,
not only the fallback — § 3 says a typed answer is lost regardless, and the instruction is
the only thing that tells a person why.

**In a private chat none of this applies.** There is one person in the room, a plain
`force_reply` already targets them, and a mention would be noise. The mention path is
group-only, selected on the chat type. The review is renaming `TgChat.isGroup` to
`isPrivate` so that `"channel"` and any future chat type fall into the quiet branch; this
code should read `isPrivate` and treat "not private" as the group case.

### This fixes existing behaviour, so it ships first

`MccPromptService.askForName` gets the same treatment and stops disturbing the whole group.
That is a fix to code already on this branch, independent of every command below, and
should be its own commit ahead of them.

## 6. `/limit`

### Step 1 — the keyboard

`/limit` lists every enabled category, one button per row, label `Category.label` plus the
current limit rendered by `formatMinor`:

```
Який ліміт змінюємо?
┌────────────────────────────┐
│ 🛒 Продукти (8 000 ₴)      │
│ 🚗 Авто (4 500 ₴)          │
│ 🎁 Подарунки (без ліміту)  │
│ ✖️ Скасувати                │
└────────────────────────────┘
```

`(без ліміту)` earns its place: it is the only view in the chat that shows which
categories can never warn about an overspend. Today that is visible only in the web UI.

Category names are capped at `MAX_CATEGORY_NAME` (40), so the longest label plus an amount
stays inside what Telegram renders on a phone.

Callback data is `lim:<categoryId>`, and `lim:x` for Cancel. The existing
`encodeCallback`/`decodeCallback` pair in `MccPrompts.kt` is untouched — its `decodeCallback`
returns `null` for a head it does not know, which is exactly what the composite dispatcher
in § 8 needs.

### Step 2 — the amount

Pressing a category sends a second message, mentioning the presser per § 5:

```
@olya, 🛒 Продукти — зараз 8 000 ₴.
Надішліть нову суму відповіддю на це повідомлення. 0 — прибрати ліміт.
```

Its `message_id` is stored (§ 7). The answer arrives as a reply carrying that id.

Cancel resolves the dialog and edits the keyboard message to say so, so a stale keyboard
is never left behind.

### Step 3 — validating the amount

`parseAmountToMinor` ([Money.kt:82](../../../src/main/kotlin/app/budget/Money.kt#L82)) is
shared with the web form and is **not** loosened for the chat. Everything below happens in
the command layer, around it.

| Input | Result |
|---|---|
| `8000`, `8 000`, `8500,50`, `8500.50` | accepted |
| `8 000 ₴`, `8000 грн`, `8000 UAH` | currency suffix stripped, then accepted — this is what a person copies straight off the button |
| `0` | limit removed; a result, not an error |
| `abc`, `вісім тисяч`, empty, control characters | rejected: re-ask with an example |
| `-500` | rejected explicitly. `parseAmountToMinor` accepts it, and `monthlyLimitMinor > 0` downstream would silently read a negative limit as "no limit" — an input accepted and then ignored is worse than one refused |
| above `MAX_LIMIT_MINOR` (1 000 000 ₴) | rejected, with the ceiling named in the message. Guards `whole * 100` in `parseAmountToMinor`, which overflows `Long` silently on a 17-digit input and yields a negative limit |

`parseAmountToMinor` throws `IllegalArgumentException` on junk, and `NumberFormatException`
— a subclass — on an integer too large for `Long`, so a single `runCatching { }` covers
both. It does not cover the overflow, which is why the explicit ceiling is checked first.

**A rejection re-asks with a fresh `force_reply`** and replaces the stored `message_id`
with the new one. Replying to the old question after that must not work — otherwise two
live reply targets exist for one dialog. § 3 is the reason this matters: a rejection that
answers with a plain message leaves the person typing into a void.

### Step 4 — writing it

Through `ingest.updateCategory(...)`, never `categories.update(...)` — the single-writer
rule. The dialog is resolved, and the keyboard message is edited to the outcome:

```
🛒 Продукти: 8 000 ₴ → 8 500 ₴ (Оля)
```

A limit set to `0` reads `🛒 Продукти: ліміт прибрано (Оля)`.

If the category was deleted while the dialog sat open, the dialog resolves with a
"category no longer exists" message rather than throwing.

## 7. Schema: `limit_prompts`

```sql
CREATE TABLE limit_prompts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    keyboard_message_id INTEGER,
    reply_message_id INTEGER,
    created_at INTEGER NOT NULL,
    resolved_at INTEGER
)
```

`CREATE UNIQUE INDEX limit_prompts_open_reply ON limit_prompts(reply_message_id)
WHERE resolved_at IS NULL` — the same partial-unique shape `mcc_prompts` uses, so two open
dialogs can never claim one reply target.

Registered in `Migrations.kt`'s `MIGRATIONS` list. **No `;` anywhere in the file's
comments** — the runner splits on `;` and a semicolon inside a `--` comment truncates the
statement and fails with an opaque JDBC error.

### No chat column, no TTL: the dialog is closed when the chat moves

The paired chat can move mid-dialog, which would otherwise leave a question whose
`reply_message_id` points into a chat nobody is listening to any more. The review of
`cc16642` is fixing the same problem for `mcc_prompts`, and this table adopts the
invariant that fix establishes:

> **An open prompt's `message_id` always refers to the currently paired chat.**

The mechanism is `ChatMoveObserver` (added by the review, in `app.notify`):

```kotlin
interface ChatMoveObserver { suspend fun onChatMoved(from: String, to: String) }
```

`TelegramUpdateHandler` calls it after a successful pairing *move* — not on a first
pairing (there is no `from`), and not on a supergroup migration (same room, message ids
stay valid).

`MccPromptService` re-homes its questions: they are worth re-asking in the new chat.
**A limit dialog is not.** A half-finished "what amount?" in a chat that no longer gets
the alerts is not worth dragging, and re-homing it would ask someone to finish a dialog
they never started. So the limit service implements `ChatMoveObserver` by **resolving**
every open dialog. Same invariant, less machinery.

It must also `editMessageText` the old keyboard message in `from`. Our `editMessageText`
sends no `reply_markup`, which drops the inline keyboard — and that matters: a keyboard
left live in the abandoned chat produces callbacks from an unpaired chat, which
`handleCallback` drops without `answerCallbackQuery`, hanging the button's spinner
forever. Each step in its own `runCatching`, so one dead message does not abort the rest.

Because every open dialog is either in the current chat or already resolved, no `chat_id`
column is needed and no expiry sweep is needed. `created_at` is kept for diagnostics only;
nothing reads it to decide anything.

The route caveat inherited from the review: `/tg/updates` awaits `handler.handle` before
responding 200, so this runs inline on the pairing message. Closing a handful of dialogs
is a few `editMessageText` calls and is not a concern at this size.

## 8. Dispatching to two handlers

`TelegramUpdateHandler` takes a single `CallbackHandler`, and `MccPromptService` is
currently it. `lim:` needs a second:

```kotlin
class CompositeCallbackHandler(private val handlers: List<CallbackHandler>) : CallbackHandler {
    override suspend fun handle(query: TgCallbackQuery): Boolean = handlers.any { it.handle(query) }
}
```

`any` short-circuits on the first handler that claims the callback, and returning `false`
from all of them keeps the existing behaviour: `TelegramUpdateHandler` answers the query
so the button's spinner stops. Wired in `Main.kt`, which is where everything is
constructed by hand.

`ChatMoveObserver` (§ 7) needs the same treatment for the same reason — `MccPromptService`
and the limit service both want one, and the handler takes a single observer. The review
is deliberately leaving that parameter as one observer and not building the composite, so
it is built here:

```kotlin
class CompositeChatMoveObserver(private val observers: List<ChatMoveObserver>) : ChatMoveObserver {
    override suspend fun onChatMoved(from: String, to: String) =
        observers.forEach { runCatching { it.onChatMoved(from, to) } }
}
```

Unlike the callback composite this one does **not** short-circuit: every observer must
run, and one that throws must not stop the next. A failed re-home leaving a stale MCC
prompt is bad; that failure also swallowing the limit-dialog cleanup is worse.

## 9. `/left`

Reads `BudgetService.monthSummary` for the current month and reports, per category:

- enabled, `monthlyLimitMinor > 0`, and **`countsAsSpending = true`**;
- remaining = limit − spent, ascending, so the tightest category is first;
- an overspent category shows a negative remainder and the ⚠️ mark.

The `countsAsSpending` filter is not optional. Spending exclusion is enforced per reader,
not upstream: `spentByCategory` still reports a self-transfer category's real amount, and
every reader that builds its own total drops it individually. A reader that forgets is a
number that disagrees with `/status`, not a compile error.

Categories with no limit are not listed one by one — they would bury the ones that matter.
They get a trailing count instead.

**Merge note:** session `mono-category-5c` is changing `Copy` on `main` — `categoriesWord`
becomes `categoriesCountWord(n: Int)`, with a Ukrainian plural helper `app.i18n.ukPlural`.
That helper does not exist on this branch. Either phrase the trailing line so no noun
follows a count, or take the dependency knowingly and reconcile at merge. Do not freeze a
single genitive plural form into a new `Copy` member — it will be wrong for 1 and for 2–4.

## 10. `/status <month>` and exact command matching

The month argument goes through `safeMonthKey`; an unparseable one is answered with the
expected format, not silently treated as the current month. No month means the current
month, exactly as today.

While adding the argument, command matching moves from `text.startsWith("/status")` to
matching the first whitespace-delimited token exactly. Today `/statuses` matches `/status`
and is answered with the month's spending. `commandForUs` has already cut any `@botname`
suffix by this point, so the token is the bare command.

## 11. `/help` and the command menu

`/help` exists (`cc16642`) and gains the new commands.

`TelegramClient` gains `setMyCommands(commands: List<BotCommand>)`, called where
`setWebhook` is called in `SettingsPage`, so Telegram shows the command list in the group's
input bar. Descriptions come from `Copy`, so re-registration must also happen when the
language setting changes — otherwise the menu keeps the language it was registered in while
every reply switches.

`setMyCommands` needs only the token; it does not read `cachedBotUsername` and therefore
does not depend on the review's fix for that cache.

## 12. Copy

New members on the `Copy` interface, implemented in `UkCopy` and `EnCopy`. The interface is
the enforcement: a missing translation is a compile error.

Roughly: the `/limit` keyboard header, the "no limit" button suffix, the cancel button and
its outcome, the amount question, five distinct rejection messages (§ 6 step 3 — not one
generic "invalid" message; each says what was wrong), the two outcome lines, the deleted-
category outcome, the `/left` header, its no-limit tail, its empty state, the `/status`
bad-month message, and the command descriptions for `setMyCommands`.

Category names and emoji are user data and never go through `Copy`. Amounts are rendered by
`formatMinor` everywhere, including inside button labels.

## 13. Testing

Fakes only — `FakeTelegramClient`, `withTestDb`, injected `Clock`. Test names are backticked
sentences.

One test per row of the § 6 validation table, including the overflow ceiling and the
negative amount.

Beyond those:

- a rejected amount leaves the dialog open and stores a **new** `reply_message_id`; a reply
  to the superseded one does nothing;
- a chat move resolves every open limit dialog and strips the keyboard from the old
  message, so a reply arriving in the abandoned chat changes no limit;
- a supergroup migration does **not** resolve open dialogs — same room, live message ids;
- a first-time pairing fires no `ChatMoveObserver`;
- `CompositeChatMoveObserver` still runs the second observer when the first throws;
- in a group, the amount question mentions the presser and sets `selective` when they have
  a username, and sends no `force_reply` when they do not;
- in a private chat the question is a plain `force_reply` with no mention;
- `askForName` does the same (the § 5 fix, tested where it lives);
- `/limit` on a category deleted mid-dialog resolves with a message instead of throwing;
- the limit is written through `IngestService`, not `CategoryRepository`;
- `/left` excludes a `countsAsSpending = false` category and orders by remaining, not spend;
- `/left` with no limits anywhere renders its empty state;
- `/status 2026-08` reports August; `/status 2026-13` is refused; `/statuses` is not `/status`;
- a `lim:` callback reaches the limit handler and an `mcc:` callback still reaches
  `MccPromptService` through the composite;
- an unknown callback head is still answered so no spinner hangs.

## 14. Out of scope

Deliberately not built, and not to be added quietly during implementation:

- `/last`, `/today`, `/week`, `/cat`, `/top` — more report commands, none of them the one
  question a family asks first.
- `/cash` — expenses Monobank never sees. Needs a transaction with no bank origin, which is
  a data-model change, not a command.
- Per-member attribution of spending, and more than one Monobank token.
- `/mute`, `/sync`.
- Owner-only commands. § 4 explains why this would be a new concept.
- Widening `allowed_updates` beyond `message` and `callback_query`. The review found that
  `TgChat.isGroup` reads `"channel"` as a private chat, which is unreachable only because
  of that narrow list. Widening it makes that finding live, and it belongs to the other
  session.

## 15. Coordination with the in-flight review

Session `mono-category-66` wrote `cc16642`, reviewed it, and closed its five findings in
`d7af593`: silence for a stranger's wrong `/start` guess (attempts still counted, so
brute-force protection is unchanged), `cachedBotUsername` keyed on the stored token so a
rotation self-heals, `TgChat.isGroup` replaced by `isPrivate`, MCC prompt re-homing via
`ChatMoveObserver`, and a question that cannot be re-asked being closed rather than left
pointing at the old chat. None of it is in this spec's scope.

**Both dependencies have landed.** Verified in the tree, not just reported:

- `ChatMoveObserver` is in `app/notify/TelegramUpdates.kt:61`, with a `NoOp`.
  `TelegramUpdateHandler` takes it as its fifth positional parameter, `chatMoves`, after
  `callbacks`, and fires it inside `handleStart` only on a re-pairing.
- `TgChat.isPrivate` is `type == null || type == "private"`
  ([TelegramUpdates.kt:19](../../../src/main/kotlin/app/notify/TelegramUpdates.kt#L19)),
  so a payload with no type still reads as private and every existing fixture keeps working.

The wiring seam is [Main.kt:156](../../../src/main/kotlin/app/Main.kt#L156), which passes
`promptService` twice — once as `callbacks`, once as `chatMoves`. Those are the two
arguments the composites in § 8 replace. `TelegramUpdateHandler` already wraps its
`onChatMoved` call in its own `runCatching`, so a composite that throws cannot break the
pairing reply.

`MccPromptService.onChatMoved` is worth reading before writing § 7's cleanup:
`refFor`, `keyboardFor` and `renderQuestion` were extracted so a re-homed question is
rebuilt identically, and `MccPromptRepository.listOpen()` was added. `limit_prompts` wants
the same `listOpen()` shape.

That session will not touch `TgCallbackQuery`, `ForceReplyMarkup`, or anything under
`limit_prompts`; this one does not touch the five items above.

## 16. Assumptions production will test

- **That privacy mode stays on.** If the owner ever turns it off, the bot starts receiving
  every message in the family group, and the § 3 reasoning — particularly "a typed answer
  is lost" — inverts. Nothing here breaks, but the instruction text becomes a lie.
- **That group members have `@username`s.** The § 5 fallback is correct but visibly worse;
  if most of the family has no username, the mention path is dead weight and buttons-only
  amount entry (§ 6, rejected option) becomes the better design.
- **That one dialog at a time is enough.** The schema allows several open dialogs, but the
  UX assumes a family group where two people rarely edit a limit in the same minute.
- **That an open dialog needs no expiry.** § 7 drops the TTL on the grounds that the
  question names its own category and amount, so answering it a week later is a deliberate
  act, not a surprise. If old dialogs turn out to be answered by accident, an expiry is
  the fix — and it should then be added for `mcc_prompts` at the same time, not just here.

## 17. Order of work

Nothing below is blocked: both dependencies landed in `d7af593` (§ 15).

1. `TgCallbackQuery.from`, `TgUser`, `selective` and the mention path; `askForName` fixed.
   This is a fix to shipped behaviour and stands alone.
2. `limit_prompts` migration and repository, with `listOpen()`; both composites (§ 8).
3. `/limit` end to end, including the `ChatMoveObserver` cleanup (§ 7) — not deferred,
   since the move case is unguarded without it.
4. `/left`.
5. `/status <month>` and exact command matching.
6. `/help` additions and `setMyCommands`.
7. CLAUDE.md updated in the same commit as the change that invalidates it — the § "paired
   chat" section gains the command surface and the reply-targeting rule.
