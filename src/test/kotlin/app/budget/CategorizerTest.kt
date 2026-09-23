package app.budget

import kotlin.test.Test
import kotlin.test.assertEquals

class CategorizerTest {

    private val rules = CategoryRules(
        mccMapping = mapOf(5411 to 1L, 4829 to 9L),
        counterpartyRules = mapOf("card:4441**1234" to 2L),
        conduitMccs = setOf(4829),
    )

    @Test
    fun `a manual row keeps whatever it has, whatever the rules say`() {
        val decision = decideCategory(5411, "card:4441**1234", manuallyCategorized = true, currentCategoryId = 7L, rules = rules)
        assertEquals(CategoryDecision(7L, CategorySource.MANUAL), decision)
    }

    @Test
    fun `a counterparty rule beats the MCC binding`() {
        val decision = decideCategory(5411, "card:4441**1234", manuallyCategorized = false, currentCategoryId = null, rules = rules)
        assertEquals(CategoryDecision(2L, CategorySource.COUNTERPARTY), decision)
    }

    @Test
    fun `the MCC binding applies when no rule matches`() {
        val decision = decideCategory(5411, null, manuallyCategorized = false, currentCategoryId = null, rules = rules)
        assertEquals(CategoryDecision(1L, CategorySource.MCC), decision)
    }

    @Test
    fun `a counterparty key that matches no rule falls through to the MCC binding`() {
        // A key present but absent from counterpartyRules — a different recipient than the
        // one a rule was ever created for — must not block layer 3 from still applying.
        val decision =
            decideCategory(5411, "card:9999**0000", manuallyCategorized = false, currentCategoryId = null, rules = rules)
        assertEquals(CategoryDecision(1L, CategorySource.MCC), decision)
    }

    @Test
    fun `a conduit code assigns nothing even though the mapping still lists it`() {
        val decision = decideCategory(4829, null, manuallyCategorized = false, currentCategoryId = null, rules = rules)
        assertEquals(CategoryDecision(null, CategorySource.NONE), decision)
    }

    @Test
    fun `an unknown code and an unknown recipient leave the row uncategorized`() {
        val decision = decideCategory(9999, "name:хтось", manuallyCategorized = false, currentCategoryId = null, rules = rules)
        assertEquals(CategoryDecision(null, CategorySource.NONE), decision)
    }
}
