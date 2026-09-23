# Design system — how to build the owner's redesign here

**[docs/design-handoff.md](design-handoff.md) is the authority.** It is the owner's own
handoff: final copy, final tokens, per-screen layout, and the reasoning behind each change.
It was written against an HTML prototype that is not published in this repository; the
handoff describes every screen in enough detail to work without it.

This file exists for one reason: the prototype is written in a style this app is not allowed
to use, and two of its instructions conflict with decisions already made here. Everything
below is the translation. Where this file and the handoff disagree on *appearance*, the
handoff wins. Where they disagree on *technique*, this file wins.

## The three translations, non-negotiable

| Prototype does | We do | Why |
|---|---|---|
| `style="…"` on every element | classes in `static/app.css` | CSP is `default-src 'none'` / `style-src 'self'`. A browser silently discards an inline style — that is how every progress bar once rendered full width in production. There is a test asserting no `style="` is emitted. |
| `<link href="fonts.googleapis.com">` | the five self-hosted `.woff2` already in `static/fonts/`, with `font-src 'self'` in the CSP | No CDN. `default-src 'none'` blocks a remote font anyway, and the numbers would silently fall back. |
| `onClick="{{ … }}"`, client-side tab state | real routes, `<form>` and `<a>`, delegation in `static/app.js` via `data-` attributes | No inline handlers. The handoff says the same in "Interactions": use real routes. |

Widths — stacked-bar segments, limit bars — travel as `data-` attributes and are applied
through the CSSOM by `app.js`, exactly as `applyBarWidths` already does.

## Two conflicts with decisions already made here

### 1. "Persist immediately (no per-row Зберегти)" — adopt it, but only for the one row

The handoff says assigning a category on Операції persists immediately, and that the *rule*
is a separate amber strip with its own `Застосувати`.

This is a **backend change, and a good one**. Today `setTransactionCategoryChoice` binds the
MCC across all history the moment a category is chosen — one `ArrowDown` on a focused select
rewrote 62 rows, which is why the current UI has a per-row apply button as a guard. The
handoff's model is better than both: choosing files **only that row**, and the blast radius
becomes an explicit second click in the rule strip.

So: implement the handoff's model, and the per-row apply button goes away. But the safety
property must survive — **nothing may bind an MCC or create a counterparty rule without an
explicit click on `Застосувати` in the rule strip.** A mis-keyed select must cost one row,
recoverable by re-picking, never 62.

### 2. The rule strip's row must not vanish

The handoff calls this out and it is worth repeating, because it is the kind of thing that
looks fine until you use it: once a category is chosen, the row no longer matches
"лише без категорії", so a naive filter drops it — and the rule strip goes with it, before it
can be applied. The row must stay until its suggestion is resolved.

We hit the same class of bug already: filing a row used to prepend it to the top of the list
so everything the owner had already skipped slid under his cursor. Keep the row where it is.

## What the handoff does not decide, and we do

- **Mobile.** The handoff leaves ≤900px open and sketches a direction. Our floor is 390×844,
  and it is a real constraint: the amount column has been entirely off-screen there before.
  Follow the handoff's sketch — summary strips to one column, category select onto its own
  line under the title — and measure the overflow rather than eyeballing it.
- **Copy.** Every user-visible string goes through `app.i18n.Copy` with both `UkCopy` and
  `EnCopy`; the interface makes a missing translation a compile error. The handoff's Ukrainian
  is final and goes in verbatim — informal "ти", nothing in Russian. `EnCopy` is a
  translation of it, not a second design.
- **Category names and emoji are user data** and never go through `Copy`.

## Number formatting — this is behaviour, not styling

The handoff is precise and the current code does not match it:

- thin space as the thousands separator, always 2 decimals, `₴` suffix: `160 000,00 ₴`
- expenses take the **true minus sign** U+2212, not a hyphen
- income is prefixed `+` and coloured `#62D19A`
- limit figures in row meta round to whole ₴
- percentages **truncate** (`Math.floor`), and every percentage on a screen comes from one
  shared computation — never two

`formatMinor` currently emits a non-breaking space and a hyphen. Changing it moves every
money assertion in the suite; that is expected, and the assertions are the point — update
them rather than working around them.

## Contrast floor

The palette runs dark. `#5F686F` on `#0A0C0E` is about 4:1 — fine for mono meta lines, not
for anything a decision depends on. Nothing essential goes below that.
