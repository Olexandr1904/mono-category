package app.budget

import app.withTestDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CounterpartyTest {

    @Test
    fun `a rule can be stored, replaced and removed`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val rent = categories.create("Оренда", "🏠", 0, 80)
        val gifts = categories.create("Подарунки", "🎁", 0, 80)
        val rules = CounterpartyRepository(db)

        rules.upsert("card:4441**1234", rent, "Петренко Іван")
        assertEquals(mapOf("card:4441**1234" to rent), rules.mapping())
        assertEquals("Петренко Іван", rules.list().single().displayName)

        rules.upsert("card:4441**1234", gifts, "Петренко Іван")
        assertEquals(mapOf("card:4441**1234" to gifts), rules.mapping())

        rules.delete("card:4441**1234")
        assertTrue(rules.list().isEmpty())
    }

    @Test
    fun `deleting a category takes its counterparty rules with it`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val rent = categories.create("Оренда", "🏠", 0, 80)
        val rules = CounterpartyRepository(db)
        rules.upsert("card:4441**1234", rent, "Петренко Іван")

        categories.delete(rent)

        assertTrue(rules.list().isEmpty())
    }
}
