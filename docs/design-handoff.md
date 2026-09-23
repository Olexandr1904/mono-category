# Handoff: Monobank expense tracker — UI redesign (4 tabs)

## Overview
Self-hosted app that pulls Monobank transactions, lets the owner categorise spending, set a monthly limit per category, and get a Telegram notification when a limit is hit. The app already works; this handoff covers a redesign of its four screens — **Огляд**, **Категорії**, **Операції**, **Налаштування** — because the original UI dumps raw form fields and duplicated tables (see "What was wrong" below).

UI language: **Ukrainian** (all copy in this document is final and should be used verbatim). Currency: UAH, symbol `₴`.

## About the design files
The original handoff bundle included `Operations.dc.html`, a **design reference written in HTML** that is not published in this repository — a prototype demonstrating the intended layout, states and interactions. It is **not production code to copy**. Recreate these screens in the app's existing environment (whatever the current stack is — Flask/Jinja templates, React, etc.), using its routing, data layer and component patterns. If there is no component layer yet, pick the framework that best fits the existing backend and implement there.

The prototype runs on hardcoded sample data. In the real app every number comes from the database / Monobank API.

## Fidelity
**High-fidelity.** Colors, typography, spacing, radii and states below are final and should be matched closely. The one thing deliberately left open is responsive behaviour (see "Responsive").

## What was wrong with the original UI (the intent behind the redesign)
1. **Операції**: every row carried a rule-suggestion sentence + a dropdown + an "Застосувати" button, repeated 10×, plus a "Дата" column repeating the same date. Noise dominated the data.
2. **Категорії**: every category was a permanently-expanded form (6 labelled inputs + 4 checkboxes + Зберегти + Видалити). Nowhere did it show the thing the app exists for — how much of the limit is used.
3. **Огляд**: a donut chart and, directly under it, a list of the exact same numbers — two representations of one dataset.
4. **Налаштування**: a bulleted checklist of ✅ emoji, then loose buttons and inputs with no grouping.

Design principles applied, keep them if you change anything: default state is read-only and quiet; controls appear when relevant; every number is monospaced with tabular figures and never wraps; colour only carries meaning (limit status, income), never decoration.

---

## Design tokens

### Colors
| Token | Hex | Use |
| --- | --- | --- |
| Canvas | `#0A0C0E` | page background; nav bar background at 92% alpha + `backdrop-filter: blur(8px)` |
| Surface raised | `#14181B` | summary strips, month stepper, status card |
| Surface list | `#121618` | list/table containers, secondary cards |
| Input surface | `#0E1113` | text inputs, selects inside cards |
| Border | `rgba(255,255,255,0.07)` | card borders (list cards use `0.06`) |
| Divider | `rgba(255,255,255,0.05)` | row separators |
| Row hover | `rgba(255,255,255,0.025)` | list row hover |
| Text primary | `#E7EAEC` | |
| Text secondary | `#C3CACF` / `#A9B1B7` | checkbox labels, table cells |
| Text muted | `#99A2A9` | secondary values, ghost button labels |
| Text dim | `#6C757C` | column headers, hints |
| Text dimmest | `#5F686F` / `#4E565C` | mono meta, group totals |
| Accent (amber) | `#E5A83C` | primary buttons, active limit warnings, focus ring |
| Accent hover | `#F0BE6B` | primary button hover; amber text on tinted backgrounds |
| Accent ink | `#171310` | text on amber buttons |
| Amber tint bg | `rgba(229,168,60,0.07)` | rule-suggestion strip, uncategorised banner |
| Amber tint border | `rgba(229,168,60,0.22)` | same |
| Success | `#62D19A` | connected states, income amounts |
| Success tint | `rgba(98,209,154,0.08–0.12)` bg, `rgba(98,209,154,0.22)` border | status pills |
| Danger | `#E77A6E` | over-limit bar, over-limit count, Видалити |
| Bar track | `rgba(255,255,255,0.07)` | progress track |
| Bar fill — in range | `#4F7F9B` | |
| Bar fill — warning | `#E5A83C` | ≥ warn threshold |
| Bar fill — over | `#E77A6E` | spent > limit |

Breakdown / donut-replacement segment palette, in order:
`#4F7F9B`, `#C97F4E`, `#62D19A`, `#E5A83C`, `#B8698A`, `#7E6BA8`, `#5D6E7A`; "Без категорії" always `#414A51`.

### Typography
- UI: **IBM Plex Sans** (400/500/600), Google Fonts. Fallback `system-ui, sans-serif`.
- All numbers, MCC codes, card PANs: **IBM Plex Mono** (400/500) with `font-variant-numeric: tabular-nums` and `white-space: nowrap`.
- Scale: page title 34px/600, `letter-spacing: -0.02em`; hero number 40px/500 mono `-0.02em`; summary number 24px/500 mono; row title 15px/500 `-0.005em`; row amount 15px/500 mono; body 13–14px/400; hint 12.5–13px/400 `#5F686F`, line-height 1.5; section kicker 11–12px/600 uppercase `letter-spacing: 0.1em` (page kicker `0.14em`); row meta 11.5px mono `letter-spacing: 0.02em`; status pill 10px/600 uppercase `0.08em`.

### Spacing / radii
- Page: `max-width 1040px`, centered, `padding: 40px 32px 96px`. Nav bar height 58px, same max-width, `padding: 0 32px`.
- Vertical rhythm between blocks: `28px` (flex column + gap). Inside cards: `14–22px`.
- Row padding: `14px 20px` (list rows), `12px 20px` (table headers), `20px 24px` (summary cells).
- Radii: cards `16px`, banners `14px`, buttons/inputs/pills `8–10px`, avatar tile `11–12px`, status pill `5px`, progress bar `999px`.
- Progress bar height: 5px (list rows), 10px (Огляд stacked bar, segments separated by `gap: 2px`).

### Number formatting
- `uk-UA` locale, always 2 decimals, thin space as thousands separator, `₴` suffix: `160 000,00 ₴`.
- Expenses use the true minus sign `−` (U+2212), not a hyphen. Income is prefixed `+` and coloured `#62D19A`.
- Limit amounts in row meta are rounded to whole ₴: `15 000 ₴`.
- Percentages **truncate** (`Math.floor`), matching the existing app: `160 000,00 / 250 000,00 → 64%`, not 65%. Every percentage on a screen must come from the same computation — never hardcode one.

---

## Screens

### 0. Global nav (all screens)
Sticky top bar, `border-bottom: 1px solid rgba(255,255,255,0.07)`, background `rgba(10,12,14,0.92)` + blur.
- Left: four text buttons — `Огляд`, `Категорії`, `Операції`, `Налаштування`. Active: `#E7EAEC`, weight 600. Inactive: `#7B848B`, weight 400. Hover: `background: rgba(255,255,255,0.05)`, radius 8px, padding `8px 12px`.
- Right: mono 12px `#5F686F` label with the connected source (`Монобанк · 5 рахунків`), then a ghost `Вийти` button (border `rgba(255,255,255,0.09)`, hover border `rgba(255,255,255,0.2)` + text `#E7EAEC`).

The month stepper (`‹  Вересень 2026  ›`) appears on Огляд and Операції: 4px-padded `#14181B` container, radius 12px, 32×32 arrow buttons, label `min-width: 148px`, centered, 14px/500. Arrows clamp at the ends of the available month range.

---

### 1. Огляд (overview)
**Purpose.** Answer two questions at a glance: where the month's money went, and what still needs attention.

**Layout** (top to bottom, gap 28px)
1. **Header row**: kicker = month (`ВЕРЕСЕНЬ 2026`, uppercase), `h1` "Огляд"; month stepper right-aligned.
2. **Hero card** (`#14181B`, radius 16, `padding: 26px 24px 8px`):
   - Kicker `ВИТРАЧЕНО`, then total in 40px mono (`250 000,00 ₴`).
   - **Stacked bar** replacing the donut: 10px tall, `border-radius: 999px`, `overflow: hidden`, one `<div>` per breakdown entry with `width: <share>%` (2 decimals) and its palette colour, `gap: 2px`. Margin `22px 0 4px`.
   - **Breakdown list**, one row per entry, grid `10px | 1fr | auto | 58px`, gap 14px, `padding: 13px 0`, `border-top: 1px solid rgba(255,255,255,0.05)`: colour chip (10×10, radius 3) · name (emoji + label, ellipsised) · amount (mono 14px) · percent (mono 13px `#6C757C`, right-aligned). The "Без категорії" row also carries the pill `ще не розібрано` (amber tint).
   - Order: real categories by spend descending, then `Інше`, then `Без категорії` last.
   - **This list is the only place these numbers appear** — do not re-render a chart legend plus a duplicate table as the old screen did.
3. **Uncategorised banner** (amber tint, radius 14, `padding: 18px 22px`, space-between, wraps):
   - Line 1 (15px/500 `#F0BE6B`): `<amount> без категорії — <pct>% з місяця`.
   - Line 2 (13px `#A08B62`): `Поки ці операції не розібрані, ліміти й статистика неповні.`
   - Primary amber button `Розібрати <n> операцій` → navigates to Операції **with the "лише без категорії" filter on** and category filter reset.
4. **Ліміти block**
   - If any category has a limit: kicker `ЛІМІТИ` + `#121618` card, one row per limited category, grid `1fr | 168px | 168px`, `padding: 16px 20px`: emoji + name · `<limit> · <pct>%` above a 5px progress bar (colour by status) · spent (mono 15px) with `залишилось <n> ₴` or `+<n> ₴ понад` under it (mono 11.5px `#5F686F`).
   - If no limits exist (**the current real state — every category limit is 0**): a single `#121618` banner: "Ліміти не встановлено" / "Без ліміту сповіщення в Telegram не надсилаються." + ghost button `Задати ліміти` → Категорії.

---

### 2. Категорії
**Purpose.** Manage categories and their monthly limits; show limit consumption. The list is read-only until a row is opened.

**Layout**
1. **Header**: kicker = month, `h1` "Категорії"; right: ghost `Стандартний набір` (replaces the whole list with the default set) + primary amber `Додати категорію` (appends a category named "Нова категорія", emoji `•`, limit 0, and opens its editor).
2. **Summary strip** — `#14181B`, radius 16, `grid-template-columns: repeat(3, 1fr)`, cells divided by `border-left: 1px solid rgba(255,255,255,0.07)`, each `padding: 20px 24px`, kicker + 24px mono value + 13px `#6C757C` hint (value and hint both `white-space: nowrap`, row `flex-wrap: wrap`):
   - `РОЗІБРАНО ЗА КАТЕГОРІЯМИ` — sum of category spends, hint `з <month total>`.
   - `З ЛІМІТОМ` — count of categories with limit > 0, hint `з <n> категорій`.
   - `ПЕРЕВИЩЕНО` — count over limit, value coloured `#E77A6E`, hint `категорій`.
3. **Category list** — `#121618` card, radius 16. Header row (11px uppercase `#5F686F`) and every data row share grid `44px | minmax(0,1fr) | 168px | 168px | 20px`, gap 16px:
   - **Tile**: 44×44, radius 12, `rgba(255,255,255,0.05)` + border `rgba(255,255,255,0.06)`, emoji 19px.
   - **Name block**: name 15px/500; status pills inline — `вимкнена` (grey outline) when disabled, `перевищено` (danger tint) when over, `<pct>%` (amber tint) when at/over the warn threshold. Second line: mono 11.5px `#5F686F` — `MCC 5811, 5812, …`, or `без кодів MCC · тільки вручну` when empty.
   - **Limit column**: with a limit → `<limit> · <pct>%` (mono 13px `#99A2A9`) above the 5px progress bar (fill capped at 100%, colour by status). Without a limit → dashed ghost button `Встановити ліміт` (border `1px dashed rgba(255,255,255,0.16)`, hover border amber). **This button must `stopPropagation()` and open the editor idempotently** — it sits inside a row that itself toggles the editor, so reusing the toggle handler cancels itself out.
   - **Amount column** (right-aligned): spent (mono 15px/500) and, when limited, `залишилось <n> ₴` / `+<n> ₴ понад` (mono 11.5px `#5F686F`).
   - **Chevron** `▾` / `▴` (10px `#5F686F`).
   - Whole row is clickable (`cursor: pointer`, hover `rgba(255,255,255,0.025)`) and toggles the editor; only one row open at a time.
4. **Inline editor** (expanded row): `background: rgba(255,255,255,0.02)`, `border-top` divider, `padding: 22px 20px 20px 80px` (left inset aligns with the row's name column), gap 18px.
   - Field grid `1fr | 88px | 160px | 160px`, gap 14px: `Назва` (text) · `Емодзі` (text, centered) · `Ліміт, ₴` (mono, placeholder `0 — без ліміту`) · `Попереджати на, %` (mono, placeholder `80`).
   - Full-width `Коди MCC` (mono, placeholder `5411, 5422, 5499`) + hint "Операції з цими кодами потраплятимуть у категорію автоматично."
   - Checkbox row (native checkboxes, `accent-color: #E5A83C`, 15×15, gap 8px, labels 13.5px `#C3CACF`): `увімкнена` · `рахувати як витрати` · divider · `сповіщення при <warn>%` · `сповіщення при 100%`. **The threshold label must read the actual warn value**, not a hardcoded 80.
   - Footer: left hint — `Сповіщення в Telegram при <warn>% і 100% ліміту.` or, when limit is 0, `Ліміт 0 — сповіщення для цієї категорії вимкнені.`; right — outlined danger `Видалити` + primary amber `Готово`.
   - Field labels: 11px/600 uppercase `#6C757C` above each input. Inputs: `#0E1113`, border `rgba(255,255,255,0.1)`, radius 9, `padding: 9px 12px`, focus border `rgba(229,168,60,0.6)`.
   - Validation: limit parses to a non-negative number (strip non-numeric), warn clamps to 1–100 with default 80.
5. The enabled categories of this screen are the **only** source for the category dropdown on Операції.

---

### 3. Операції
**Purpose.** Assign categories to transactions, fast, with as little chrome per row as possible.

**Layout**
1. **Header**: kicker `РАХУНОК ФОП · UAH`, `h1` "Операції", month stepper right.
2. **Summary strip** (same 3-cell component as Категорії): `ВИТРАТИ ЗА МІСЯЦЬ` (total) · `БЕЗ КАТЕГОРІЇ` (amount + `<pct>% з місяця`) · `НЕ РОЗІБРАНО` (live count + `з <n> операцій`).
3. **Filter bar** — one wrapping row, gap 10px:
   - Category `<select>` (`Усі категорії` + enabled categories) and sort `<select>` (`Спочатку нові` / `Спочатку старі` / `За сумою`): `#14181B`, border `rgba(255,255,255,0.09)`, radius 10, `padding: 9px 34px 9px 14px`, `appearance: none` with a `▾` glyph absolutely positioned 13px from the right, `pointer-events: none`.
   - 1px × 24px divider.
   - Two toggle pills — `Лише без категорії`, `Показати надходження`. Each renders a 6px dot + label. Off: transparent, border `rgba(255,255,255,0.09)`, text `#99A2A9`, dot `#3A4147`. On: tinted background + coloured border + coloured text + coloured dot (amber for the first, `#62D19A` for the second). Default: "лише без категорії" **on**, "показати надходження" **off** (matches the app).
   - `Скинути` (underlined text button) appears **only when a filter differs from the default**.
4. **Grouped list** — one group per day, `gap: 8px`:
   - Group header (outside the card, `padding: 18px 20px 10px`): left = 12px/600 uppercase date `3 вересня, четвер`; right = mono 12px `#4E565C` day total. This replaces the repeating "Дата" column.
   - Group card `#121618`, radius 16; rows separated by `border-top: 1px solid rgba(255,255,255,0.05)`.
   - Row grid `40px | minmax(0,1fr) | auto | auto`, gap 16, `padding: 14px 20px`, hover `rgba(255,255,255,0.025)`, `transition: background 120ms ease`:
     - 40×40 radius-11 monogram tile (first letters of the description, 13px/600 `#9EA7AD`).
     - Title 15px/500, ellipsised; `холд` pill when the transaction is on hold (10px uppercase `#C79A4A`, border `rgba(199,154,74,0.35)`). Meta line: mono 11.5px `#5F686F` — `MCC 4829 · 03.09.2026`.
     - Category `<select>`, `min-width: 168px`. Unassigned: transparent with `1px dashed rgba(255,255,255,0.18)`, text `#7B848B`, first option `Обрати категорію`; hover border amber. Assigned: `rgba(255,255,255,0.05)` fill, solid border, primary text.
     - Amount right-aligned in a `min-width: 132px` cell: expenses `#E7EAEC`, income `#62D19A`.
5. **Rule suggestion** — the old UI printed "створить правило для …" + an "Застосувати" button on *every* row. Instead: after a category is chosen **and** the transaction has ≥2 siblings (same counterparty, or same MCC for MCC-derived descriptions), show one amber strip under that row (`margin: 0 20px 14px`, `padding: 10px 14px`, radius 10): text `Створити правило для «<name>» — операцій: <n>` (or `Застосувати до всіх операцій з MCC <code>`), then ghost `Не зараз` + amber `Застосувати`.
   - **Applying** sets the same category on every matching transaction and closes the strip.
   - **Important:** the row must stay visible while its suggestion is open even though it no longer matches the "лише без категорії" filter (in the prototype: `if (ruleFor === row.id) return true` at the top of the filter predicate). Otherwise the row vanishes the instant a category is chosen and the rule can never be applied.
6. **Empty state** (`#121618` card, `padding: 72px 24px`, centered): "Нічого не знайдено" + "За цими фільтрами операцій немає. Спробуйте інший місяць або скиньте фільтри."

---

### 4. Налаштування
**Purpose.** Connection state, accounts, sync, secrets — in that order of importance; secrets last because they are touched once.

1. **Header**: kicker `МОНОБАНК · TELEGRAM`, `h1` "Налаштування".
2. **Status card** (`#14181B`): 8px green dot + "Усе підключено" (15px/500), then a wrapping row of pills (success tint, radius 8, `padding: 6px 11px`, 12.5px `#A9B1B7`, leading `✓` in `#62D19A`): `Токен Монобанку`, `Токен Telegram-бота`, `Telegram-чат`, `Вебхук Telegram`, `Вебхук Монобанку`. Any not-configured item should render in the muted/danger variant instead of green — the summary line then states what is missing.
3. **Рахунки Монобанку** — kicker + `#121618` table, grid `minmax(0,1fr) | 150px | 160px | 110px`, gap 16px. Columns: `Рахунок` (masked PAN, mono 13.5px `#C3CACF`), `Тип` (13.5px `#99A2A9`), `Баланс` (mono 14px, right-aligned), `Врахований` (native checkbox, right-aligned).
   - Footer band (`rgba(255,255,255,0.02)`, top border): left hint "Знятий прапорець прибирає весь рахунок з витрат — і минулі місяці теж, не тільки майбутні синхронізації."; right `Разом <sum of counted balances>` (mono 13px `#99A2A9`) + amber `Зберегти`.
   - Sample data for layout only — invented, not from any real account: `444111******0000 / madeInUkraine / 0 ₴`, `444111******1111 / black / 12 000,00 ₴`, `487407******2222 / yellow / 8 000,00 ₴`, `— / fop / 0 ₴`, `444111******3333 / eAid / 0,00 ₴`, all counted.
4. **Two cards side by side** (`grid-template-columns: 1fr 1fr`, gap 16):
   - **Синхронізація** — title + green pill `вебхук активний`; mono 12.5px result line `Востаннє: 369 нових · 0 оновлених · 0 без змін · 5 рахунків`; hint `Синхронізація охоплює лише <month> — так влаштований застосунок.`; buttons pinned to the bottom (`margin-top: auto`): amber `Синхронізувати операції` + ghost `Перереєструвати вебхук`.
   - **Telegram** — title + green pill `чат під'єднано`; hint "Сповіщення про перевищення лімітів надходять у під'єднаний чат."; buttons: outlined `Надіслати тестове сповіщення` + ghost `Перепід'єднати`.
5. **Токени** — kicker + `#121618` card. Hint "Залиш поле порожнім, щоб не чіпати збережене значення. Токени зберігаються в зашифрованому вигляді." Two fields in a 2-col grid, each label followed inline by a green `налаштовано` pill when a value is stored: `Токен Монобанку` (placeholder `u…`) and `Токен Telegram-бота` (placeholder `123456:ABC…`), mono inputs. Right-aligned amber `Зберегти токени`. Never render a stored token value — placeholder only.
6. **Мова** — single banner row: "Мова інтерфейсу" + `<select>` (`Українська`, `English`) + ghost `Зберегти`.

---

## Interactions & behavior
- **Tab switching** is client-side state in the prototype; in the app use real routes (`/overview`, `/categories`, `/transactions`, `/settings`).
- **Month stepper** clamps to the available range; changing the month refetches that month's transactions and recomputes every number on the screen (the app only tracks one month at a time).
- **Assigning a category** on Операції: persist immediately (no per-row Зберегти), then evaluate the rule suggestion (see 3.5). The row stays put until the suggestion is resolved, then leaves the filtered list.
- **Rule apply**: server-side, one request that categorises all matching transactions of the month and stores the rule for future syncs.
- **Category editor**: fields apply as typed in the prototype; in the app either keep that (with debounce) or keep `Готово` as an explicit save — but the row summary must reflect the saved limit immediately, since the progress bar is the point of the screen.
- **Limit crossing** is what triggers the Telegram message; the checkboxes `сповіщення при <warn>%` / `при 100%` control which crossing notifies. A limit of 0 means "no limit" and disables notifications for that category — say so in the UI (see the editor footer hint).
- **Account checkbox** changes the historical expense totals too (per the app's own warning) — expect every figure on Огляд/Операції to change after saving.
- Hover states: rows lighten to `rgba(255,255,255,0.025)`; ghost buttons brighten border to `rgba(255,255,255,0.2)` and text to `#E7EAEC`; amber buttons go `#E5A83C → #F0BE6B`; inputs focus to border `rgba(229,168,60,0.6)`. Transitions: `background 120ms ease` on rows; the rest are instant.
- No loading skeletons are specified. If sync/fetch is slow, disable the triggering button and show its label in a pending form (`Синхронізую…`) rather than covering the screen.

## State
Per screen: active tab/route; selected month; Операції — category filter, sort, `лише без категорії` (default true), `показати надходження` (default false), pending rule target; Категорії — the category collection, which row is expanded; Налаштування — accounts with their `counted` flags, language, token form fields (write-only).
Derived (never stored twice): month total, per-category spend, percentages, limit consumption, uncategorised amount/count, counted-balance total. Every percentage on a screen comes from one shared computation with `Math.floor`.

## Responsive
The prototype targets desktop (1040px content column). Not specified below ~900px. If mobile matters: collapse the 3-cell summary strips to one column, drop the Категорії grid to name + amount with the limit bar full-width under it, and move the transaction category select onto its own line under the title.

## Assets
None. Only Google Fonts (IBM Plex Sans + IBM Plex Mono) and text glyphs (`‹ › ▾ ▴ ✓ −`). Category emoji are user data stored per category. No icon library is needed.

## Files
- `Operations.dc.html` — the HTML prototype of all four screens that this document was written from. It is not published here; everything it showed is specified above.

## Sample data
All figures in this document are invented for layout and must not be read as real data: month total `250 000,00 ₴`; per-category spend Здоров'я та краса `35 000,00`, Різне `30 000,00`, Шопінг `6 000,00`, Ресторани та бари `4 200,00`, Продукти `3 800,00`, Авто `0`; `Інше 11 000,00`; `Без категорії 160 000,00` (64%, 10 transactions); MCC sets for Авто (`5511, 5521, 5532, 5533, 5541, 9222`) and Ресторани та бари (`5811, 5812, 5813, 5814`); all limits `0`; the five accounts and balances; the sync line and status checks.
