package app.i18n

import kotlin.test.Test
import kotlin.test.assertEquals

class PluralTest {

    private fun categories(n: Int) = ukPlural(n, "категорія", "категорії", "категорій")

    @Test
    fun `ukPlural picks the form the number agrees with`() {
        assertEquals("категорій", categories(0))
        assertEquals("категорія", categories(1))
        assertEquals("категорії", categories(2))
        assertEquals("категорії", categories(4))
        assertEquals("категорій", categories(5))
        assertEquals("категорій", categories(13))
    }

    @Test
    fun `the eleven-to-fourteen band overrides the last digit`() {
        assertEquals("категорій", categories(11))
        assertEquals("категорій", categories(12))
        assertEquals("категорій", categories(14))
        assertEquals("категорія", categories(21))
        assertEquals("категорії", categories(22))
        assertEquals("категорія", categories(101))
        assertEquals("категорій", categories(111))
    }

    @Test
    fun `the Перевищено caption agrees with the figure above it`() {
        // 2026-09-22 production walkthrough, finding 4: the live page read "2 категорій".
        assertEquals("категорія", UkCopy.categoriesCountWord(1))
        assertEquals("категорії", UkCopy.categoriesCountWord(2))
        assertEquals("категорій", UkCopy.categoriesCountWord(13))
    }

    @Test
    fun `the nav caption agrees with the account count`() {
        assertEquals("Монобанк · 1 рахунок", UkCopy.headerMeta(1))
        assertEquals("Монобанк · 2 рахунки", UkCopy.headerMeta(2))
        assertEquals("Монобанк · 5 рахунків", UkCopy.headerMeta(5))
    }

    @Test
    fun `the of-N captions take the genitive`() {
        assertEquals("з 1 категорії", UkCopy.ofCategoriesCount(1))
        assertEquals("з 2 категорій", UkCopy.ofCategoriesCount(2))
        assertEquals("з 13 категорій", UkCopy.ofCategoriesCount(13))
        assertEquals("з 1 операції", UkCopy.ofTransactionsCount(1))
        assertEquals("з 373 операцій", UkCopy.ofTransactionsCount(373))
    }

    @Test
    fun `English keeps its own singular`() {
        assertEquals("category", EnCopy.categoriesCountWord(1))
        assertEquals("categories", EnCopy.categoriesCountWord(2))
        assertEquals("of 1 category", EnCopy.ofCategoriesCount(1))
        assertEquals("of 1 transaction", EnCopy.ofTransactionsCount(1))
        assertEquals("of 2 transactions", EnCopy.ofTransactionsCount(2))
    }
}
