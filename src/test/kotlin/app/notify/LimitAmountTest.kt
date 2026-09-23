package app.notify

import kotlin.test.Test
import kotlin.test.assertEquals

class LimitAmountTest {

    private fun ok(raw: String) = (parseLimitAmount(raw) as AmountResult.Ok).minor

    @Test
    fun `a plain number is an amount`() {
        assertEquals(800_000L, ok("8000"))
    }

    @Test
    fun `the separators formatMinor emits are accepted back`() {
        assertEquals(800_000L, ok("8 000"))
        assertEquals(850_050L, ok("8500,50"))
        assertEquals(850_050L, ok("8500.50"))
    }

    /** What a person copies straight off the button, which renders through formatMinor. */
    @Test
    fun `a currency suffix is stripped rather than refused`() {
        assertEquals(800_000L, ok("8 000 ₴"))
        assertEquals(800_000L, ok("8000 грн"))
        assertEquals(800_000L, ok("8000 UAH"))
    }

    /**
     * RegexOption.IGNORE_CASE is Pattern.CASE_INSENSITIVE, which is ASCII-only unless
     * UNICODE_CASE is set too — so "uah" folded in any case while Cyrillic "грн" only ever
     * matched lowercase.
     */
    @Test
    fun `the currency suffix folds case in Cyrillic too, not only ASCII`() {
        assertEquals(800_000L, ok("8000 ГРН"))
        assertEquals(800_000L, ok("8000 Грн"))
    }

    @Test
    fun `zero is removing the limit, not a bad answer`() {
        assertEquals(0L, ok("0"))
    }

    @Test
    fun `letters and words are not amounts`() {
        assertEquals(AmountResult.NotANumber, parseLimitAmount("abc"))
        assertEquals(AmountResult.NotANumber, parseLimitAmount("вісім тисяч"))
        assertEquals(AmountResult.NotANumber, parseLimitAmount(""))
        assertEquals(AmountResult.NotANumber, parseLimitAmount("   "))
        assertEquals(AmountResult.NotANumber, parseLimitAmount("8000 грн на місяць"))
    }

    /** Stripped rather than refused, the way a category name already is — an invisible
     *  character carried along by a paste should not be an error message. */
    @Test
    fun `a stray control character is tolerated`() {
        assertEquals(800_000L, ok("8000\u0000"))
    }

    /**
     * parseAmountToMinor accepts a negative, and `monthlyLimitMinor > 0` downstream would
     * then read it as "no limit" — an input accepted and then silently ignored, which is
     * worse than one refused.
     */
    @Test
    fun `a negative amount is refused, not quietly treated as no limit`() {
        assertEquals(AmountResult.Negative, parseLimitAmount("-500"))
    }

    /**
     * parseAmountToMinor's `whole * 100` overflows Long on a seventeen-digit input and
     * yields a negative limit with no exception at all, so the ceiling is checked on the
     * text before anything is parsed.
     */
    @Test
    fun `an absurd amount is refused before it can overflow`() {
        assertEquals(AmountResult.TooLarge, parseLimitAmount("99999999999999999"))
        assertEquals(AmountResult.TooLarge, parseLimitAmount("2000000"))
    }

    @Test
    fun `the ceiling itself is allowed`() {
        assertEquals(MAX_LIMIT_MINOR, ok("1000000"))
    }
}
