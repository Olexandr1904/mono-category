package app.budget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoneyTest {
    @Test
    fun `whole hryvnia hides kopecks`() {
        // U+202F (narrow no-break space) between thousands groups, not a plain space —
        // design-handoff.md's "thin space" thousands separator, and still non-breaking so
        // "−201 100 ₴" cannot wrap mid-number (2026-09-03 UX review §5).
        assertEquals("12 430 ₴", formatMinor(1_243_000))
    }

    @Test
    fun `kopecks are shown when non-zero`() {
        assertEquals("12 430,50 ₴", formatMinor(1_243_050))
    }

    @Test
    fun `zero and negative amounts format correctly`() {
        assertEquals("0 ₴", formatMinor(0))
        // The true minus sign U+2212, not the ASCII hyphen — design-handoff.md.
        assertEquals("−240 ₴", formatMinor(-24_000))
    }

    @Test
    fun `signed formatting prefixes a plus for income and leaves the true minus for expenses`() {
        assertEquals("+240 ₴", formatSignedMinor(24_000))
        assertEquals("−240 ₴", formatSignedMinor(-24_000))
        assertEquals("+0 ₴", formatSignedMinor(0))
    }

    @Test
    fun `parses plain digits, spaces, dots and commas`() {
        assertEquals(1_500_000, parseAmountToMinor("15000"))
        assertEquals(1_500_000, parseAmountToMinor("15 000"))
        assertEquals(1_500_050, parseAmountToMinor("15000.50"))
        assertEquals(1_500_050, parseAmountToMinor("15000,50"))
    }

    @Test
    fun `rejects garbage`() {
        assertFailsWith<IllegalArgumentException> { parseAmountToMinor("abc") }
    }

    @Test
    fun `parses a non-breaking thousands separator`() {
        assertEquals(1_500_000, parseAmountToMinor("15 000"))
    }

    @Test
    fun `sharePercent rounds half-up instead of truncating`() {
        // 2026-09-22 production walkthrough, finding 3: the real September 2026 shares,
        // rescaled to invented amounts with the same percentages.
        // Truncation printed 13%, 6% and 12% for these, and the whole column summed to 97%.
        val month = 10_000_000L
        assertEquals(14, sharePercent(1_394_000, month))  // 13,94%
        assertEquals(7, sharePercent(674_000, month))     // 6,74%
        assertEquals(13, sharePercent(1_252_000, month))  // 12,52%
        assertEquals(23, sharePercent(2_317_000, month))  // 23,17%, unchanged either way
    }

    /**
     * Rounding each share on its own fixed the 97% the walkthrough measured but not the
     * thing it was measuring: six equal categories each round to 17% and print 102%, three
     * equal ones print 99%. A column of percentages of one whole has to be apportioned, not
     * rounded entry by entry — the leftover points go to the largest remainders.
     */
    @Test
    fun `share percentages of one month add up to exactly 100`() {
        assertEquals(100, sharePercents(List(6) { 1_000_000L }, 6_000_000).sum())
        assertEquals(100, sharePercents(List(3) { 1_000_000L }, 3_000_000).sum())
        assertEquals(100, sharePercents(List(7) { 1_000_000L }, 7_000_000).sum())
        // The real September 2026 breakdown's shape, the one that printed 97%.
        val month = 10_000_000L
        val real = listOf(2_317_000L, 1_394_000L, 1_252_000L, 674_000L, 4_363_000L)
        assertEquals(100, sharePercents(real, month).sum())
    }

    @Test
    fun `apportioning gives the spare points to the largest remainders`() {
        // Three equal thirds: 33,33% each, so one category has to print 34 and the order
        // decides which. Ties go to the earliest, which is the largest by spend — the list
        // arrives sorted, so the biggest category absorbs the rounding, not the smallest.
        assertEquals(listOf(34, 33, 33), sharePercents(List(3) { 1_000_000L }, 3_000_000))
        // 0,4% is still 0% on its own, but it holds the largest remainder here, so it is
        // the one point that has to go somewhere.
        assertEquals(listOf(50, 50, 0), sharePercents(listOf(4_980L, 4_980L, 40L), 10_000))
    }

    @Test
    fun `apportioning ignores a non-positive total and negative amounts`() {
        assertEquals(listOf(0, 0), sharePercents(listOf(5_000L, 5_000L), 0))
        assertEquals(listOf(0, 0), sharePercents(listOf(5_000L, 5_000L), -1))
        assertEquals(emptyList(), sharePercents(emptyList(), 10_000))
        // A refund among the spending: it contributes nothing rather than a negative share.
        assertEquals(0, sharePercents(listOf(-5_000L, 10_000L), 10_000).first())
    }

    @Test
    fun `sharePercent still floors at zero and ignores a non-positive total`() {
        assertEquals(0, sharePercent(-5_000, 10_000_000))
        assertEquals(0, sharePercent(5_000, 0))
        assertEquals(0, sharePercent(5_000, -1))
    }

    @Test
    fun `percentOf keeps truncating so a limit alert never fires early`() {
        // Deliberately NOT rounded: percentOf feeds Thresholds, and rounding 79,6% of a
        // limit up to 80% would claim a threshold the spend has not crossed.
        assertEquals(79, percentOf(796_000, 1_000_000))
        assertEquals(148, percentOf(2_975_268, 2_000_000))
    }

    @Test
    fun `isTinyShare still catches spend too small to render as a percent`() {
        val month = 10_000_000L
        assertTrue(isTinyShare(1_000, month))       // 0,003% -> still 0%
        assertFalse(isTinyShare(0, month))
        assertFalse(isTinyShare(82_000, month))     // 0,82% -> now rounds to 1%
    }

    @Test
    fun `parses the narrow no-break separator formatMinor now emits`() {
        assertEquals(1_500_000, parseAmountToMinor("15 000"))
    }
}
