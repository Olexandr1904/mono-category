package app.web

import app.budget.CategorySpending
import app.budget.MonthSummary

/**
 * One row of the Огляд hero card's breakdown — the donut's replacement
 * (design-handoff.md §1: "the donut goes away... a breakdown list — colour chip, name,
 * mono amount, right-aligned mono percentage. That list is the only place those numbers
 * appear"). Built once per request from [MonthSummary]; the stacked bar and the list below
 * it are both rendered from this exact list, so they can never disagree.
 *
 * [paletteSlot] is `0..6` for a real category or the "Інше" remainder (selects one of the
 * seven fixed `.seg-N` classes in app.css) and `null` for "Без категорії", which always
 * renders with the dedicated `.seg-uncat` class instead of a palette slot.
 */
data class BreakdownEntry(
    val label: String,
    val amountMinor: Long,
    val paletteSlot: Int?,
    val isUncategorized: Boolean = false,
)

/** design-handoff.md's segment palette, in order: `#4F7F9B, #C97F4E, #62D19A, #E5A83C,
 *  #B8698A, #7E6BA8, #5D6E7A` — mirrored in app.css as `.seg-0`..`.seg-6`. Colours never
 *  ride an inline style (CSP: style-src 'self'), so a fixed, CSS-authored class per slot
 *  is the only per-category colour this app can express; [paletteSlot] just picks one. */
private const val BREAKDOWN_PALETTE_SIZE = 7

// The last palette slot is reserved for "Інше" (categories beyond the top six), never
// assigned to a real category — see assignSlots below. This mirrors the real sample data
// in design-handoff.md's "Sample vs real data": six named categories, then one "Інше".
private const val BREAKDOWN_MAX_COLORED_REAL = BREAKDOWN_PALETTE_SIZE - 1
private const val BREAKDOWN_OTHER_SLOT = BREAKDOWN_MAX_COLORED_REAL

/**
 * The month's spend broken into: real categories ranked by spend descending (up to six,
 * each keeping a colour tied to its own stable [app.budget.Category.position] rather than
 * its rank, exactly like the donut this replaces did — a category must not change colour
 * just because spending shifted), then "Інше" for the rest, then "Без категорії" last
 * (design-handoff.md §1's ordering, verbatim). Empty when nothing was spent this month —
 * callers must skip the hero card's bar and list entirely in that case, same contract the
 * donut it replaces had.
 */
fun monthBreakdown(summary: MonthSummary, otherLabel: String, uncategorizedLabel: String): List<BreakdownEntry> {
    if (summary.totalSpentMinor <= 0) return emptyList()

    // Finding 3 (CRITICAL) of the 2026-09-04 review, decided: a countsAsSpending = false
    // category is left out of this list entirely, not shown with a Copy.notCountedPill the
    // way the Telegram /status line does. Its own row still shows its real spend, unaffected
    // (see Category.kt's doc comment) — this only decides what appears in the one place
    // these numbers get charted. The stacked bar's segment widths and this list's percent
    // column are both computed as a share of totalSpentMinor, which already excludes this
    // money; showing the category here without including it in that total would either
    // corrupt the widths (segments no longer summing to the bar) or need a second, competing
    // total, which is exactly the "two representations that could disagree" the donut this
    // replaces was retired for. The Telegram line has no such shared-total constraint, so it
    // can afford the pill; this list can't.
    val ranked = summary.categories
        .filter { it.category.countsAsSpending && it.spentMinor > 0 }
        .sortedWith(compareByDescending<CategorySpending> { it.spentMinor }.thenBy { it.category.id })
    val top = ranked.take(BREAKDOWN_MAX_COLORED_REAL)
    val otherMinor = ranked.drop(BREAKDOWN_MAX_COLORED_REAL).sumOf { it.spentMinor }

    val entries = assignSlots(top)
        .map { (row, slot) -> BreakdownEntry(row.category.label, row.spentMinor, slot) }
        .toMutableList()
    if (otherMinor > 0) entries += BreakdownEntry(otherLabel, otherMinor, BREAKDOWN_OTHER_SLOT)
    if (summary.uncategorizedMinor > 0) {
        entries += BreakdownEntry(uncategorizedLabel, summary.uncategorizedMinor, paletteSlot = null, isUncategorized = true)
    }
    return entries
}

/**
 * Deterministic, collision-free slot assignment within one breakdown: each category's
 * preferred slot is `position % 6`. When two categories land on the same slot, the one
 * drawn later (i.e. ranked lower this month) moves to the next free slot, wrapping around
 * — so the outcome never depends on map/set iteration order.
 */
private fun assignSlots(rows: List<CategorySpending>): List<Pair<CategorySpending, Int>> {
    val used = mutableSetOf<Int>()
    return rows.map { row ->
        var slot = row.category.position.mod(BREAKDOWN_MAX_COLORED_REAL)
        while (slot in used) slot = (slot + 1) % BREAKDOWN_MAX_COLORED_REAL
        used += slot
        row to slot
    }
}

/** CSS class carrying this entry's colour — see app.css's `.seg-0`..`.seg-6`/`.seg-uncat`. */
fun BreakdownEntry.colorClass(): String = if (isUncategorized) "seg-uncat" else "seg-${paletteSlot}"
